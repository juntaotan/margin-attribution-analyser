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
CREATE TABLE production_order
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_no VARCHAR(100)   NOT NULL,
    date                DATE           NOT NULL,
    product_no          VARCHAR(100)   NOT NULL,
    product_num         NUMERIC(18, 6) NOT NULL,
    product_department  VARCHAR(50)    NOT NULL,
    bom_no              VARCHAR(100)   NOT NULL,

    CONSTRAINT uk_production_product UNIQUE (production_order_no, product_no),
    CONSTRAINT ck_production_product_num CHECK (product_num > 0)
);

CREATE INDEX idx_production_bom_no ON production_order (bom_no);

CREATE TABLE material_consumption
(
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    material_consumption_no VARCHAR(100)   NOT NULL,
    production_order_no     VARCHAR(100)   NOT NULL,
    product_no              VARCHAR(100)   NOT NULL,
    material_no             VARCHAR(100)   NOT NULL,
    material_num            NUMERIC(18, 6) NOT NULL,
    material_total_cost     NUMERIC(18, 6) NOT NULL,

    CONSTRAINT uk_material_consumption_line UNIQUE (material_consumption_no, material_no),
    CONSTRAINT fk_material_consumption_production
        FOREIGN KEY (production_order_no, product_no)
        REFERENCES production_order (production_order_no, product_no),
    CONSTRAINT ck_material_consumption_quantity CHECK (material_num > 0),
    CONSTRAINT ck_material_consumption_total_cost CHECK (material_total_cost >= 0)
);

CREATE INDEX idx_material_consumption_production
    ON material_consumption (production_order_no, product_no);
CREATE INDEX idx_material_consumption_product_no ON material_consumption (product_no);
CREATE INDEX idx_material_consumption_material_no ON material_consumption (material_no);

CREATE TABLE inventory_usage
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    date               DATE         NOT NULL,
    movement_no        VARCHAR(100) NOT NULL,
    product_no         VARCHAR(100) NOT NULL,
    product_num        NUMERIC(18, 6),
    product_total_cost NUMERIC(18, 6),
    order_no           VARCHAR(100) NOT NULL,

    CONSTRAINT pk_inventory_usage PRIMARY KEY (id, date),
    CONSTRAINT fk_inventory_usage_production
        FOREIGN KEY (order_no, product_no)
        REFERENCES production_order (production_order_no, product_no)
)
PARTITION BY HASH (date);

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
-- Sales and receivables
-- =========================================================
CREATE TABLE sales_order
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_order_no       VARCHAR(100)   NOT NULL,
    date                DATE           NOT NULL,
    movement_no         VARCHAR(100)   NOT NULL,
    product_no          VARCHAR(100)   NOT NULL,
    product_num         NUMERIC(18, 6) NOT NULL,
    product_total_price NUMERIC(18, 6) NOT NULL,

    CONSTRAINT uk_sale_order_product UNIQUE (sale_order_no, product_no),
    CONSTRAINT ck_sales_product_quantity CHECK (product_num > 0),
    CONSTRAINT ck_sales_product_total_amount CHECK (product_total_price >= 0)
);

CREATE INDEX idx_sales_product_no ON sales_order (product_no);

CREATE TABLE account_receivables
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    date                  DATE           NOT NULL,
    account_receivable_no VARCHAR(100)   NOT NULL,
    product_no            VARCHAR(100)   NOT NULL,
    product_num           NUMERIC(18, 6) NOT NULL,
    product_total_price   NUMERIC(18, 6) NOT NULL,
    sale_order_no         VARCHAR(100)   NOT NULL,

    CONSTRAINT pk_account_receivables PRIMARY KEY (id, date),
    CONSTRAINT uk_account_receivable_sale_product
        UNIQUE (account_receivable_no, sale_order_no, product_no, date),
    CONSTRAINT fk_account_receivables_sales_order
        FOREIGN KEY (sale_order_no, product_no)
        REFERENCES sales_order (sale_order_no, product_no),
    CONSTRAINT ck_account_receivable_product_quantity CHECK (product_num > 0),
    CONSTRAINT ck_account_receivable_product_total_amount CHECK (product_total_price >= 0)
)
PARTITION BY HASH (date);

CREATE INDEX idx_account_receivables_product_no ON account_receivables (product_no);
CREATE INDEX idx_account_receivables_sale_order_no ON account_receivables (sale_order_no);

-- =========================================================
-- Purchases and payables
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

CREATE TABLE account_payables
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    date                DATE           NOT NULL,
    account_payable_no  VARCHAR(100)   NOT NULL,
    product_no          VARCHAR(100)   NOT NULL,
    product_num         NUMERIC(18, 6) NOT NULL,
    product_total_price NUMERIC(18, 6) NOT NULL,
    purchase_order_no   VARCHAR(100)   NOT NULL,

    CONSTRAINT pk_account_payables PRIMARY KEY (id, date),
    CONSTRAINT uk_account_payable_purchase_material
        UNIQUE (account_payable_no, purchase_order_no, product_no, date),
    CONSTRAINT fk_account_payables_purchases
        FOREIGN KEY (purchase_order_no, product_no)
        REFERENCES purchases (purchase_order_no, product_no),
    CONSTRAINT ck_account_payable_material_quantity CHECK (product_num > 0),
    CONSTRAINT ck_account_payable_material_total_cost CHECK (product_total_price >= 0)
)
PARTITION BY HASH (date);

CREATE INDEX idx_account_payables_material_no ON account_payables (product_no);
CREATE INDEX idx_account_payables_purchase_order_no ON account_payables (purchase_order_no);

-- Create all hash partitions required by the partitioned warehouse tables.
DO $$
DECLARE
    parent_table TEXT;
    partition_number INTEGER;
    partition_count CONSTANT INTEGER := 8;
BEGIN
    FOREACH parent_table IN ARRAY ARRAY[
        'inventory_usage',
        'account_receivables',
        'account_payables'
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
