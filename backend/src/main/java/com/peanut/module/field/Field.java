package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "field")
public class Field {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    @Column(columnDefinition = "geometry(Polygon,4326)")
    Polygon geometry;
    BigDecimal areaMu;
    String soilType;
    String variety;
    LocalDate sowDate;
    String ownerName;
    String remark;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;

    protected Field() {
    }

    void update(FieldRequest request, Polygon polygon, BigDecimal area, OffsetDateTime now) {
        name = request.name();
        geometry = polygon;
        areaMu = area;
        soilType = request.soilType();
        variety = request.variety();
        sowDate = request.sowDate();
        ownerName = request.ownerName();
        remark = request.remark();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
