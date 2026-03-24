package io.agroal.tests;


import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.WithByteman;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceListener;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.agroal.narayana.NarayanaTransactionIntegration;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;


@WithByteman
@BMUnitConfig(debug = true)
abstract class XAReaperRaceITBase {

    private static final Logger logger = Logger.getLogger( XAReaperRaceITBase.class.getName() );

    abstract String xaDataSourceClassName();
    abstract String jdbcUrl();
    abstract String username();
    abstract String password();
    abstract String slowSQL();

    /**
     * Whether this driver throws from the slow SQL when the reaper fires mid-execution.
     *
     * Most drivers (PostgreSQL, MySQL, MariaDB) let the slow SQL complete normally
     * because the XAConnectionLock read lock blocks end(TMFAIL) until the SQL finishes.
     *
     * MSSQL throws because MSDTC actively cancels the distributed transaction
     * independently of the Java-level lock.
     */
    boolean slowSQLThrowsOnReaperTimeout() {
        return false;
    }

    String createTableDDL() {
        return "CREATE TABLE IF NOT EXISTS xa_reaper_test ( id INT PRIMARY KEY, val VARCHAR(100) )";
    }

    String truncateTableSQL() {
        return "DELETE FROM xa_reaper_test";
    }

    protected AgroalDataSource createXADataSource() throws SQLException {
        TransactionManager txManager = com.arjuna.ats.jta.TransactionManager.transactionManager();
        TransactionSynchronizationRegistry txSyncRegistry =
                new com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionSynchronizationRegistryImple();

        return AgroalDataSource.from( new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration( cp -> cp
                        .maxSize( 1 )
                        .transactionIntegration( new NarayanaTransactionIntegration( txManager, txSyncRegistry ) )
                        .connectionFactoryConfiguration( cf -> cf
                                .connectionProviderClassName( xaDataSourceClassName() )
                                .jdbcUrl( jdbcUrl() )
                                .principal( new NamePrincipal( username() ) )
                                .credential( new SimplePassword( password() ) )
                        )
                ), new LoggingListener() );
    }





    protected void verifyXASupported( AgroalDataSource dataSource ) {
        TransactionManager txManager = com.arjuna.ats.jta.TransactionManager.transactionManager();
        try {
            txManager.setTransactionTimeout( 10 );
            txManager.begin();
            dataSource.getConnection().close();
            txManager.rollback();
        } catch ( Exception e ) {
            try { txManager.rollback(); } catch ( Exception ignore ) {}
            assumeTrue( false, "XA not supported: " + e.getMessage() );
        }
    }

    @Test
    @BMScript("reaper")
    public void testInterleave() throws Exception {

        TransactionManager txManager = com.arjuna.ats.jta.TransactionManager.transactionManager();

        try ( AgroalDataSource dataSource = createXADataSource() ) {
            verifyXASupported( dataSource );

            // Create table using plain Statement (not PreparedStatement)
            // so the Byteman rule on PreparedStatementWrapper.execute() does not fire
            try ( Connection setup = dataSource.getConnection() ) {
                setup.createStatement().execute( createTableDDL() );
                setup.createStatement().execute( truncateTableSQL() );
            }

            // Long timeout so the real TransactionReaper does not interfere
            txManager.setTransactionTimeout( 30 );
            txManager.begin();
            Transaction t = txManager.getTransaction();
            Connection connection = dataSource.getConnection();

            // Prepare INSERT but do not execute yet
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO xa_reaper_test (id, val) VALUES (1, 'interleave-test')" );

            // Simulate the reaper: t.rollback() triggers end(TMFAIL) then rollback()
            // on the XA resource.  Byteman pauses the reaper at
            // BaseXAResource.rollback() AT ENTRY, creating a window where
            // end(TMFAIL) has completed but rollback has not yet executed.
            Thread reaper = new Thread( () -> {
                try {
                    t.rollback();
                } catch ( SystemException e ) {
                    logger.warning( "Reaper rollback exception: " + e.getMessage() );
                }
            }, "test-reaper" );
            reaper.start();

            // Byteman rendezvous: the app thread waits at execute() AT ENTRY
            // until the reaper reaches rollback() AT ENTRY (after end(TMFAIL)).
            //
            try {
                ps.execute();
                fail( "INSERT must be rejected after end(TMFAIL) — connection should be poisoned" );
            } catch ( SQLException e ) {
                logger.info( "INSERT correctly rejected: " + e.getMessage() );
            }

            reaper.join( 10_000 );

            // The reaper thread rolled back the TX, but it is still associated
            // with the main thread.  Suspend it so getConnection() does not
            // throw "Failing fast as the transaction has rolled back".
            try { txManager.suspend(); } catch ( SystemException ignore ) { }

            // Verify no data leaked to the database
            try ( Connection verify = dataSource.getConnection() ) {
                ResultSet rs = verify.createStatement()
                        .executeQuery( "SELECT COUNT(*) FROM xa_reaper_test" );
                rs.next();
                int count = rs.getInt( 1 );
                if ( count != 0 ) {
                    fail( "Data leak: xa_reaper_test has " + count + " rows but should have 0" );
                }
            }
        }
    }
    
    
    




    private static class LoggingListener implements AgroalDataSourceListener {
        @Override public void onWarning(String message) { logger.warning( "Agroal: " + message ); }
        @Override public void onWarning(Throwable throwable) { logger.warning( "Agroal: " + throwable.getMessage() ); }
    }
}
