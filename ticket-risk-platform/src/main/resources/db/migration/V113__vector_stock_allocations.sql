-- Additive: old allocation headers remain unchanged. -1 denotes a new vector header.
ALTER TABLE tb_ticket_sku ADD COLUMN single_purchase_limit INT NOT NULL DEFAULT 100;
ALTER TABLE tb_reservation_dispatch ADD COLUMN vector_inventory_enabled INT NOT NULL DEFAULT 0;
CREATE TABLE tb_stock_bucket_allocation_item (
 reservation_id VARCHAR(36) NOT NULL,
 bucket_id INT NOT NULL,
 quantity INT NOT NULL,
 PRIMARY KEY(reservation_id,bucket_id),
 CHECK(bucket_id>=0 AND bucket_id<16), CHECK(quantity>0)
);
