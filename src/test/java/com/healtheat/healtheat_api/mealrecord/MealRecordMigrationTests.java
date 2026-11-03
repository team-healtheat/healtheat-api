package com.healtheat.healtheat_api.mealrecord;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class MealRecordMigrationTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Test void refusesLegacyDuplicatesWithoutDeletingThem() { assertLegacyConflictIsPreserved(false); }
    @Test void refusesMissingLegacyDateWithoutDeletingIt() { assertLegacyConflictIsPreserved(true); }
    private void assertLegacyConflictIsPreserved(boolean missingDate) {
        String schema="record_policy_test_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(schema.matches("record_policy_test_[0-9a-f]{32}"));
        jdbc.execute("create schema "+schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("4").load().migrate();
            jdbc.update("insert into "+schema+".users(id,email,password_hash) values (1,?,?)",UUID.randomUUID()+"@example.test","hash");
            if (missingDate) jdbc.update("insert into "+schema+".meal_records(user_id,meal_date,meal_type) values (1,null,'LUNCH')");
            else jdbc.update("insert into "+schema+".meal_records(user_id,meal_date,meal_type) values (1,'2026-11-03','LUNCH'),(1,'2026-11-03','LUNCH')");
            assertThrows(FlywayException.class,() -> Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate());
            assertEquals(missingDate ? 1L : 2L,jdbc.queryForObject("select count(*) from "+schema+".meal_records",Long.class));
        } finally {
            jdbc.execute("drop schema "+schema+" cascade");
        }
    }
}
