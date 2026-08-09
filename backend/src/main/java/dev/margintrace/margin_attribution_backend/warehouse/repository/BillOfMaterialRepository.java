package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillOfMaterialRepository extends JpaRepository<BillOfMaterial, Long> {
    Optional<BillOfMaterial> findByBomNoAndProductNoAndMaterialNo(
            String bomNo,
            String productNo,
            String materialNo
    );

    List<BillOfMaterial> findAllByBomNoAndProductNo(String bomNo, String productNo);
}
