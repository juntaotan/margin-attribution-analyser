-- =========================================================
-- State machine for import job
-- =========================================================
CREATE TABLE import_job
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    original_filename  VARCHAR(255) NOT NULL,
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
            'PENDING',
            'VALIDATING',
            'VALIDATED',
            'VALIDATING_FAILED',
            'STORING',
            'STORED',
            'STORING_FAILED',
            'TRANSFORMING',
            'TRANSFORMED',
            'TRANSFORMING_FAILED',
            'WRITING_TO_DATALAKE',
            'WRITE_SUCCESS',
            'WRITE_FAILED',
            'CANCELLED'
        )
    )
);

CREATE INDEX idx_import_job_status
    ON import_job (status);

-- =========================================================
-- Inventory / Production foundation tables
-- =========================================================

CREATE TABLE production_order
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    year                INT NOT NULL,
    month              INT NOT NULL,
    date               DATE NOT NULL,
    production_order_no VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_total_cost  NUMERIC(18, 6),
    product_department  VARCHAR(50)  NOT NULL,

    CONSTRAINT uk_production_product
        UNIQUE (production_order_no, product_no),
    CONSTRAINT chk_production_order_month
        CHECK (month BETWEEN 1 AND 12)
    );

CREATE TABLE inventory_usage
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    year               INT NOT NULL,
    month              INT NOT NULL,
    date               DATE NOT NULL,
    movement_no        VARCHAR(100) NOT NULL,
    product_no         VARCHAR(100) NOT NULL,
    product_num        NUMERIC(18, 6),
    product_total_cost NUMERIC(18, 6),
    order_no           VARCHAR(100) NOT NULL,

    CONSTRAINT pk_inventory_movement
        PRIMARY KEY (id, year, month),
    CONSTRAINT fk_inventory_usage_production
        FOREIGN KEY (order_no, product_no)
        REFERENCES production_order(production_order_no, product_no),
    CONSTRAINT chk_inventory_movement_month
        CHECK (month BETWEEN 1 and 12)
)
PARTITION BY RANGE (year, month);


CREATE TABLE bill_of_material
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bom_no         VARCHAR(100)   NOT NULL,
    product_no     VARCHAR(100)   NOT NULL,
    material_no    VARCHAR(100)   NOT NULL,
    material_usage NUMERIC(18, 6) NOT NULL,

    CONSTRAINT ck_bom_material_usage
        CHECK (material_usage > 0)
);


-- =========================================================
-- Sales module
-- =========================================================

CREATE TABLE sales_order
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    year                INT NOT NULL,
    month               INT NOT NULL,
    date                DATE NOT NULL,
    movement_no         VARCHAR(100) NOT NULL,
    sale_order_no       VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),

    CONSTRAINT uk_sale_order_product
        UNIQUE (sale_order_no, product_no),

    CONSTRAINT chk_sales_order_month
        CHECK (month BETWEEN 1 AND 12)
);

CREATE TABLE account_receivables
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    year                  INT NOT NULL,
    month                 INT NOT NULL,
    date                  DATE NOT NULL,
    account_receivable_no VARCHAR(100) NOT NULL,
    product_no            VARCHAR(100) NOT NULL,
    product_num           NUMERIC(18, 6),
    product_unit_price    NUMERIC(18, 6),
    product_total_price   NUMERIC(18, 6),
    sale_order_no         VARCHAR(100) NOT NULL,

    CONSTRAINT pk_account_receivables
        PRIMARY KEY (id, year, month),

    CONSTRAINT fk_account_receivables_sales_order
        FOREIGN KEY (sale_order_no, product_no)
        REFERENCES sales_order (sale_order_no, product_no),
    CONSTRAINT chk_account_receivables_month
        CHECK (month BETWEEN 1 AND 12)
)
PARTITION BY RANGE (year, month);

-- =========================================================
-- Purchase module
-- =========================================================

CREATE TABLE purchases
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    year                INT NOT NULL,
    month               INT NOT NULL,
    date                DATE NOT NULL,
    purchase_order_no   VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_price  NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),

    CONSTRAINT uk_purchase_order_product
        UNIQUE (purchase_order_no, product_no),

    CONSTRAINT chk_purchases_month
        CHECK (month BETWEEN 1 AND 12)
);

CREATE TABLE account_payables
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    year                INT NOT NULL,
    month               INT NOT NULL,
    date                DATE NOT NULL,
    account_payable_no  VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_price  NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),
    purchase_order_no   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_account_payables
        PRIMARY KEY (id, year, month),

    CONSTRAINT fk_account_payables_purchases
        FOREIGN KEY (purchase_order_no, product_no)
        REFERENCES purchases (purchase_order_no, product_no),

    CONSTRAINT chk_account_payables_month
        CHECK (month BETWEEN 1 AND 12)
)
PARTITION BY RANGE (year, month);

-- =========================================================
-- Warehouse extensions
-- =========================================================

-- A production result must identify the BOM version used to make the product.
-- NOT VALID preserves any legacy rows loaded before BOM attribution was available,
-- while PostgreSQL still enforces the checks for all new or updated rows.
ALTER TABLE production
    ADD COLUMN bom_no VARCHAR(100),
    DROP COLUMN product_total_cost;

ALTER TABLE production
    ADD CONSTRAINT ck_production_bom_no_required
        CHECK (bom_no IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_production_product_num
        CHECK (product_num IS NOT NULL AND product_num > 0) NOT VALID;

CREATE INDEX idx_production_bom_no
    ON production (bom_no);


-- Introduce the warehouse business key for a production material issue.
-- Legacy relationship columns remain available but become optional so that the
-- four-field warehouse record can be loaded independently.
ALTER TABLE material_consumption
    ADD COLUMN material_consumption_no VARCHAR(100),
    ALTER COLUMN production_order_no DROP NOT NULL,
    ALTER COLUMN product_no DROP NOT NULL,
    ALTER COLUMN movement_no DROP NOT NULL;

ALTER TABLE material_consumption
    ADD CONSTRAINT uk_material_consumption_line
        UNIQUE (material_consumption_no, material_no),
    ADD CONSTRAINT ck_material_consumption_no_required
        CHECK (material_consumption_no IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_material_consumption_quantity
        CHECK (material_num IS NOT NULL AND material_num > 0) NOT VALID,
    ADD CONSTRAINT ck_material_consumption_total_cost
        CHECK (material_total_cost IS NOT NULL AND material_total_cost >= 0) NOT VALID;

CREATE INDEX idx_material_consumption_material_no
    ON material_consumption (material_no);


-- A purchase line represents one material on one purchase order. The existing
-- product columns retain their physical names for compatibility with the core schema.
ALTER TABLE purchases
    ADD CONSTRAINT ck_purchase_material_quantity
        CHECK (product_num IS NOT NULL AND product_num > 0) NOT VALID,
    ADD CONSTRAINT ck_purchase_material_total_cost
        CHECK (product_total_price IS NOT NULL AND product_total_price >= 0) NOT VALID;

CREATE INDEX idx_purchases_material_no
    ON purchases (product_no);


-- An accounts-payable line represents one purchased material from one purchase
-- order on one payable document. Product columns retain their core-schema names.
ALTER TABLE account_payables
    ADD CONSTRAINT uk_account_payable_purchase_material
        UNIQUE (account_payable_no, purchase_order_no, product_no),
    ADD CONSTRAINT ck_account_payable_material_quantity
        CHECK (product_num IS NOT NULL AND product_num > 0) NOT VALID,
    ADD CONSTRAINT ck_account_payable_material_total_cost
        CHECK (product_total_price IS NOT NULL AND product_total_price >= 0) NOT VALID;

CREATE INDEX idx_account_payables_material_no
    ON account_payables (product_no);

CREATE INDEX idx_account_payables_purchase_order_no
    ON account_payables (purchase_order_no);


-- A sales line represents one product on one sales order. The total price is
-- the sales amount for this product line, rather than the whole order.
ALTER TABLE sales
    ADD CONSTRAINT ck_sales_product_quantity
        CHECK (product_num IS NOT NULL AND product_num > 0) NOT VALID,
    ADD CONSTRAINT ck_sales_product_total_amount
        CHECK (product_total_price IS NOT NULL AND product_total_price >= 0) NOT VALID;

CREATE INDEX idx_sales_product_no
    ON sales (product_no);


-- An accounts-receivable line represents one sold product from one sales order
-- on one receivable document. Its total price is the sales amount for this line.
ALTER TABLE account_receivables
    ADD CONSTRAINT uk_account_receivable_sale_product
        UNIQUE (account_receivable_no, sale_order_no, product_no),
    ADD CONSTRAINT ck_account_receivable_product_quantity
        CHECK (product_num IS NOT NULL AND product_num > 0) NOT VALID,
    ADD CONSTRAINT ck_account_receivable_product_total_amount
        CHECK (product_total_price IS NOT NULL AND product_total_price >= 0) NOT VALID;

CREATE INDEX idx_account_receivables_product_no
    ON account_receivables (product_no);

CREATE INDEX idx_account_receivables_sale_order_no
    ON account_receivables (sale_order_no);
