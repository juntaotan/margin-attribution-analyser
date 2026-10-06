-- =========================================================
-- Import job state machine
-- =========================================================
CREATE TABLE import_job
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    original_filename  VARCHAR(255) NOT NULL,
    mapping_table_name VARCHAR(100) NOT NULL,
    mapping_result     BOOLEAN NOT NULL,
    file_extension     VARCHAR(20),
    storage_object_key VARCHAR(500) NOT NULL,
    status             VARCHAR(40) NOT NULL,
    error_code         VARCHAR(100),
    error_message      VARCHAR(1000),
    imported_rows      BIGINT NOT NULL DEFAULT 0,
    rejected_rows      BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMP WITH TIME ZONE,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version            BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_import_job_status CHECK (
        status IN (
            'PENDING', 'VALIDATING', 'VALIDATED', 'VALIDATING_FAILED',
            'STORING', 'STORED', 'STORING_FAILED',
            'TRANSFORMING', 'TRANSFORMED', 'TRANSFORMING_FAILED',
            'WRITING_TO_DATALAKE', 'WRITE_SUCCESS', 'WRITE_FAILED', 'CANCELLED'
        )
    )
);

CREATE INDEX idx_import_job_status ON import_job (status);

-- =========================================================
-- Production and inventory
-- =========================================================
-- One row per material code. Descriptive attributes may be absent in source ERP exports.
CREATE TABLE material_master
(
    material_no    VARCHAR(100) NOT NULL,
    material_name  VARCHAR(200),
    material_type  VARCHAR(30),
    base_uom       VARCHAR(16),
    specification  VARCHAR(255),
    material_group VARCHAR(100),
    status         VARCHAR(20),

    CONSTRAINT pk_material_master PRIMARY KEY (material_no),
    CONSTRAINT ck_material_no_not_blank CHECK (btrim(material_no) <> '')
)
PARTITION BY HASH (material_no);

CREATE TABLE inventory_usage
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    date               DATE         NOT NULL,
    movement_no        VARCHAR(100) NOT NULL,
    product_no         VARCHAR(100) NOT NULL,
    product_num        NUMERIC(18, 6),
    product_total_cost NUMERIC(18, 6),
    order_no           VARCHAR(100) NOT NULL,
    material_no        VARCHAR(100),
    material_num       NUMERIC(18, 6),
    material_total_cost NUMERIC(18, 6),

    CONSTRAINT pk_inventory_usage PRIMARY KEY (id, date),
    CONSTRAINT uk_inventory_material_line UNIQUE (date, movement_no, material_no),
    CONSTRAINT ck_inventory_material_values CHECK (
        material_no IS NULL OR (material_num > 0 AND material_total_cost >= 0)
    )
)
PARTITION BY HASH (date);

CREATE INDEX idx_inventory_usage_product_order ON inventory_usage (product_no, order_no);
CREATE INDEX idx_inventory_usage_material_no ON inventory_usage (material_no);

CREATE TABLE bill_of_material
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bom_no         VARCHAR(100)   NOT NULL,
    product_no     VARCHAR(100)   NOT NULL,
    material_no    VARCHAR(100)   NOT NULL,
    material_usage NUMERIC(18, 6) NOT NULL,

    CONSTRAINT uk_bom_product_material UNIQUE (bom_no, product_no, material_no),
    CONSTRAINT ck_bom_material_usage CHECK (material_usage > 0)
);

-- =========================================================
-- Sales and product costs
-- =========================================================
CREATE TABLE sales_details
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    date          DATE           NOT NULL,
    movement_no   VARCHAR(100)   NOT NULL,
    product_no    VARCHAR(100)   NOT NULL,
    product_num   NUMERIC(18, 6) NOT NULL,
    total_revenue NUMERIC(18, 6) NOT NULL,
    total_cost    NUMERIC(18, 6) NOT NULL,

    CONSTRAINT pk_sales_details PRIMARY KEY (id, date),
    CONSTRAINT uk_sales_details_movement_product
        UNIQUE (movement_no, product_no, date),
    CONSTRAINT ck_sales_details_product_quantity CHECK (product_num > 0),
    CONSTRAINT ck_sales_details_total_revenue CHECK (total_revenue >= 0),
    CONSTRAINT ck_sales_details_total_cost CHECK (total_cost >= 0)
)
PARTITION BY HASH (date);

CREATE INDEX idx_sales_details_product_no ON sales_details (product_no);
CREATE INDEX idx_sales_details_movement_no ON sales_details (movement_no);

-- =========================================================
-- Purchases
-- =========================================================
CREATE TABLE purchases
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_no   VARCHAR(100)   NOT NULL,
    date                DATE           NOT NULL,
    product_no          VARCHAR(100)   NOT NULL,
    product_num         NUMERIC(18, 6) NOT NULL,
    product_total_price NUMERIC(18, 6) NOT NULL,

    CONSTRAINT uk_purchase_order_product UNIQUE (purchase_order_no, product_no),
    CONSTRAINT ck_purchase_material_quantity CHECK (product_num > 0),
    CONSTRAINT ck_purchase_material_total_cost CHECK (product_total_price >= 0)
);

CREATE INDEX idx_purchases_material_no ON purchases (product_no);

-- Create all hash partitions required by the partitioned warehouse tables.
DO $$
DECLARE
    parent_table TEXT;
    partition_number INTEGER;
    partition_count CONSTANT INTEGER := 8;
BEGIN
    FOREACH parent_table IN ARRAY ARRAY[
        'material_master',
        'inventory_usage',
        'sales_details'
    ]
    LOOP
        FOR partition_number IN 0..(partition_count - 1) LOOP
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF %I
                 FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                parent_table || '_h' || partition_number,
                parent_table,
                partition_count,
                partition_number
            );
        END LOOP;
    END LOOP;
END
$$;
