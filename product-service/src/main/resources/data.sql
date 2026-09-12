-- Seed catalog so the platform is testable immediately after startup.
INSERT INTO products (id, name, description, price, category, image_url, active)
VALUES
  (1, 'Wireless Headphones', 'Over-ear Bluetooth headphones with noise cancellation', 79.99, 'ELECTRONICS', 'https://picsum.photos/seed/headphones/300', true),
  (2, 'Mechanical Keyboard', 'RGB backlit mechanical keyboard, blue switches', 59.49, 'ELECTRONICS', 'https://picsum.photos/seed/keyboard/300', true),
  (3, 'Running Shoes', 'Lightweight breathable running shoes', 89.00, 'FOOTWEAR', 'https://picsum.photos/seed/shoes/300', true),
  (4, 'Coffee Maker', '12-cup programmable drip coffee maker', 45.99, 'HOME', 'https://picsum.photos/seed/coffee/300', true),
  (5, 'Yoga Mat', 'Non-slip eco-friendly yoga mat', 24.99, 'FITNESS', 'https://picsum.photos/seed/yoga/300', true),
  (6, 'Backpack', 'Water-resistant 30L travel backpack', 39.95, 'ACCESSORIES', 'https://picsum.photos/seed/backpack/300', true)
ON CONFLICT (id) DO NOTHING;

SELECT setval('products_id_seq', (SELECT MAX(id) FROM products));
