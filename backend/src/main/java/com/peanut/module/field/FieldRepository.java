package com.peanut.module.field;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FieldRepository extends JpaRepository<Field, Long> {
    @Query(value = "SELECT CAST(ST_Area(ST_Transform(ST_GeomFromEWKB(:geometry), 32650)) / 666.67 AS numeric)",
            nativeQuery = true)
    BigDecimal calculateAreaMu(@Param("geometry") byte[] geometry);

    @Query("""
            SELECT field FROM Field field
            WHERE field.name ILIKE :pattern ESCAPE '!' OR field.variety ILIKE :pattern ESCAPE '!'
            """)
    Page<Field> search(@Param("pattern") String pattern, Pageable pageable);

    @Query(value = "SELECT id FROM field WHERE id = :id FOR KEY SHARE", nativeQuery = true)
    List<Long> lockForEvent(@Param("id") long id);

    @Query(value = """
            SELECT NULLIF(BTRIM(variety), '') AS variety, count(*) AS count, sum(area_mu) AS "areaMu"
            FROM field
            GROUP BY NULLIF(BTRIM(variety), '')
            ORDER BY sum(area_mu) DESC, variety
            """, nativeQuery = true)
    List<VarietyTotals> summarizeByVariety();

    interface VarietyTotals {
        String getVariety();
        long getCount();
        BigDecimal getAreaMu();
    }
}
