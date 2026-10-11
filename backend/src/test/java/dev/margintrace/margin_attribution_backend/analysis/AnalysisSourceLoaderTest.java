package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisSourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated warehouse snapshot retains selected-instance records across reporting boundaries. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.datasource.url=jdbc:h2:mem:analysis-source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"},showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalysisSourceLoader.class)
class AnalysisSourceLoaderTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisSourceLoader loader;
    @Test void loadsImmutableOriginalRecordsAndCallerCoverageDeclaration() {
        for(int i=0;i<2;i++)jdbc.update("INSERT INTO inventory_usage(id,date,movement_no,order_no,product_no,material_no,material_num,material_total_cost,product_num,product_total_cost) VALUES (?,?,?,?,?,?,?,?,?,?)",
                1000+i,LocalDate.of(2026,8+i,15),"movement-"+i,"P1","A","R1",10,20,10,20);
        var frozen=loader.load(false);assertEquals(2,frozen.issues().size());assertFalse(frozen.completeCoverage());
        assertEquals("1000@2026-08-15",frozen.issues().getFirst().lineId());assertEquals("P1",frozen.issues().getLast().productionId());
        jdbc.update("DELETE FROM inventory_usage WHERE id=1000");assertEquals(2,frozen.issues().size());assertThrows(UnsupportedOperationException.class,()->frozen.issues().clear());
    }
}
