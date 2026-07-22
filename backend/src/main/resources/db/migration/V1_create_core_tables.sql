-- =========================================================
-- Inventory / Production foundation tables
-- =========================================================

CREATE TABLE inventory_movement
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movement_no        VARCHAR(100) NOT NULL,
    product_no         VARCHAR(100) NOT NULL,
    product_num        NUMERIC(18, 6),
    product_unit_cost  NUMERIC(18, 6),
    product_total_cost NUMERIC(18, 6),
    order_no           VARCHAR(100) NOT NULL,

    CONSTRAINT uk_inventory_movement_product
        UNIQUE (movement_no, order_no, product_no)
);


CREATE TABLE production
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_no VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_cost   NUMERIC(18, 6),
    product_total_cost  NUMERIC(18, 6),
    product_department  VARCHAR(50)  NOT NULL,

    CONSTRAINT uk_production_product
        UNIQUE (production_order_no, product_no)
);


CREATE TABLE bill_of_material
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bom_no         VARCHAR(100)   NOT NULL,
    product_no     VARCHAR(100)   NOT NULL,
    material_no    VARCHAR(100)   NOT NULL,
    material_usage NUMERIC(18, 6) NOT NULL,

    CONSTRAINT uk_bom_product_material
        UNIQUE (bom_no, product_no, material_no),

    CONSTRAINT ck_bom_material_usage
        CHECK (material_usage > 0)
);


-- =========================================================
-- Sales module
-- =========================================================

CREATE TABLE sales
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_order_no       VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_price  NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),
    movement_no         VARCHAR(100),

    CONSTRAINT uk_sale_order_product
        UNIQUE (sale_order_no, product_no),

    CONSTRAINT fk_sales_inventory_movement
        FOREIGN KEY (movement_no, sale_order_no, product_no)
            REFERENCES inventory_movement (
                                           movement_no,
                                           order_no,
                                           product_no
                )
);


CREATE TABLE account_receivables
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_receivable_no VARCHAR(100) NOT NULL,
    product_no            VARCHAR(100) NOT NULL,
    product_num           NUMERIC(18, 6),
    product_unit_price    NUMERIC(18, 6),
    product_total_price   NUMERIC(18, 6),
    sale_order_no         VARCHAR(100) NOT NULL,

    CONSTRAINT fk_account_receivables_sales
        FOREIGN KEY (sale_order_no, product_no)
            REFERENCES sales (
                              sale_order_no,
                              product_no
                )
);


-- =========================================================
-- Purchase module
-- =========================================================

CREATE TABLE purchases
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_no   VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_price  NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),
    movement_no         VARCHAR(100),

    CONSTRAINT uk_purchase_order_product
        UNIQUE (purchase_order_no, product_no),

    CONSTRAINT fk_purchases_inventory_movement
        FOREIGN KEY (movement_no, purchase_order_no, product_no)
            REFERENCES inventory_movement (
                                           movement_no,
                                           order_no,
                                           product_no
                )
);


CREATE TABLE account_payables
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_payable_no  VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    product_num         NUMERIC(18, 6),
    product_unit_price  NUMERIC(18, 6),
    product_total_price NUMERIC(18, 6),
    purchase_order_no   VARCHAR(100) NOT NULL,

    CONSTRAINT fk_account_payables_purchases
        FOREIGN KEY (purchase_order_no, product_no)
            REFERENCES purchases (
                                  purchase_order_no,
                                  product_no
                )
);


-- =========================================================
-- Material consumption
-- =========================================================

CREATE TABLE material_consumption
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_no VARCHAR(100) NOT NULL,
    product_no          VARCHAR(100) NOT NULL,
    material_no         VARCHAR(100) NOT NULL,
    material_num        NUMERIC(18, 6),
    material_unit_cost  NUMERIC(18, 6),
    material_total_cost NUMERIC(18, 6),
    movement_no         VARCHAR(100) NOT NULL,

    CONSTRAINT fk_material_consumption_inventory_movement
        FOREIGN KEY (
                     movement_no,
                     production_order_no,
                     product_no
            )
            REFERENCES inventory_movement (
                                           movement_no,
                                           order_no,
                                           product_no
                ),

    CONSTRAINT fk_material_consumption_production
        FOREIGN KEY (
                     production_order_no,
                     product_no
            )
            REFERENCES production (
                                   production_order_no,
                                   product_no
                )
);