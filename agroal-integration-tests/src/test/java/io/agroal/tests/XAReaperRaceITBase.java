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
    
    
    
    
                txManager.begin();
                Transaction t = txManager.getTransaction();
                Connection connection = dataSource.getConnection();
    
                System.out.println("Attempting to insert");
                PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO jta_test (some_string) VALUES ('test')");;
    
    
                Thread reaper = new Thread(() -> {
                    try {
                        t.rollback();
                    } catch (SystemException e) {
                        fail("Could not rollback from 'reaper'");
                    }
                });
                reaper.start();
    
                try {
                    // TODO this is the thing we want to do after the verification in prepareStatement
                    preparedStatement.execute();
                } catch (SQLException e) {
                    // This is expected
                    return;
                }
                fail("The end should have failed not have been allowed to happen");
                t.commit();
    
                try (Connection connection2 = dataSource.getConnection()) {
                    PreparedStatement preparedStatement1 = connection2.prepareStatement("select * from jta_test");
                    ResultSet resultSet = preparedStatement1.executeQuery();
                    while (resultSet.next()) {
                        System.out.println(resultSet.getString(1));
                        fail("The commit should have failed not have been allowed to happen");
                    }
                }
            }
        }
    
    
    




    private static class LoggingListener implements AgroalDataSourceListener {
        @Override public void onWarning(String message) { logger.warning( "Agroal: " + message ); }
        @Override public void onWarning(Throwable throwable) { logger.warning( "Agroal: " + throwable.getMessage() ); }
    }
}
