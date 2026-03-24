package io.agroal.tests;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

import jakarta.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * XA reaper race integration test against MSSQL using Testcontainers.
 * Uses WAITFOR DELAY for slow SQL that holds the read lock past the transaction timeout.
 *
 * MSSQL XA requires the JDBC XA stored procedures to be installed.
 * The init script (mssql-xa-init.sql) installs them via sp_sqljdbc_xa_install.
 */
@Tag( "testcontainers" )
@Testcontainers
class MSSQLXAReaperRaceIT extends XAReaperRaceITBase {

    private static final Logger logger = Logger.getLogger( MSSQLXAReaperRaceIT.class.getName() );

    @Container
    static MSSQLServerContainer mssql = new MSSQLServerContainer( "mcr.microsoft.com/mssql/server:2022-latest" )
            .acceptLicense()
            .withInitScript( "mssql-xa-init.sql" );

    @Override
    String xaDataSourceClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerXADataSource";
    }

    @Override
    String jdbcUrl() {
        return mssql.getJdbcUrl();
    }

    @Override
    String username() {
        return mssql.getUsername();
    }

    @Override
    String password() {
        return mssql.getPassword();
    }

    @Override
    String slowSQL() {
        return "WAITFOR DELAY '00:00:04'";
    }

    /**
     * MSSQL's MSDTC actively cancels the distributed transaction when the
     * reaper fires, causing the in-flight WAITFOR DELAY to throw:
     * "The Microsoft Distributed Transaction Coordinator (MS DTC) has cancelled the distributed transaction."
     *
     * This differs from PostgreSQL/MySQL/MariaDB where the XAConnectionLock
     * read lock blocks end(TMFAIL) and the slow SQL completes normally.
     */
    @Override
    boolean slowSQLThrowsOnReaperTimeout() {
        return true;
    }

    @Override
    String createTableDDL() {
        return "IF OBJECT_ID('xa_reaper_test', 'U') IS NULL " +
               "CREATE TABLE xa_reaper_test ( id INT PRIMARY KEY, val VARCHAR(100) )";
    }

    @Override
    String truncateTableSQL() {
        return "IF OBJECT_ID('xa_reaper_test', 'U') IS NOT NULL DELETE FROM xa_reaper_test";
    }

   
}
