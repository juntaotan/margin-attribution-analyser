-- Reuse material descriptions and base_uom; store only the missing local embedding attributes.
-- Existing rows remain unknown until explicit values are imported.
ALTER TABLE material_master ADD COLUMN supplier VARCHAR(200);
ALTER TABLE material_master ADD COLUMN source_kind VARCHAR(16);
ALTER TABLE material_master ADD COLUMN unit_cost NUMERIC(18, 6);
ALTER TABLE material_master ADD CONSTRAINT ck_material_source_kind CHECK (source_kind = 'PURCHASED' OR source_kind = 'INTERNAL');
ALTER TABLE material_master ADD CONSTRAINT ck_material_unit_cost CHECK (unit_cost >= 0);
