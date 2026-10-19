package dev.margintrace.margin_attribution_backend.importation.model;

/**
 * Kinds of business data that can be imported.
 */
public enum DataSetType {
    // Sales and product costs
    SALES_DETAIL,
    // Purchase part
    PURCHASE,
    // Inventory management and production part
    MATERIAL_MASTER,
    INVENTORY_MOVEMENT,
    BOM
}
