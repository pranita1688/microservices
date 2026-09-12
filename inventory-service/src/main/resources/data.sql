-- Seed stock levels matching the product IDs seeded in product-service.
INSERT INTO inventory_items (id, product_id, quantity_available, quantity_reserved, version)
VALUES
  (1, 1, 100, 0, 0),
  (2, 2, 75, 0, 0),
  (3, 3, 50, 0, 0),
  (4, 4, 40, 0, 0),
  (5, 5, 200, 0, 0),
  (6, 6, 60, 0, 0)
ON CONFLICT (id) DO NOTHING;

SELECT setval('inventory_items_id_seq', (SELECT MAX(id) FROM inventory_items));
