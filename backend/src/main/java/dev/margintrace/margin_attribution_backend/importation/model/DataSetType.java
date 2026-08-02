package dev.margintrace.margin_attribution_backend.importation.model;

public enum DataSetType {
    // Sales part
    SALES,
    ACCOUNT_RECEIVABLE,
    // Purchase part
    PURCHASE,
    ACCOUNT_PAYABLE,
    // Inventory management and production part
    INVENTORY_MOVEMENT,
    PRODUCTION,
    BOM,
    MATERIAL_CONSUMPTION
}
