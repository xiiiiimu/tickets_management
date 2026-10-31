-- Optional inventory buckets. Empty tables leave the original single-row stock path intact.
CREATE TABLE IF NOT EXISTS tb_ticket_stock_bucket (
 sku_id VARCHAR(36) NOT NULL,
 bucket_id INT NOT NULL,
 total_stock INT NOT NULL,
 remaining_stock INT NOT NULL,
 version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(sku_id,bucket_id),
 CHECK(total_stock>=0), CHECK(remaining_stock>=0), CHECK(remaining_stock<=total_stock)
);
CREATE TABLE IF NOT EXISTS tb_stock_bucket_allocation (
 reservation_id VARCHAR(36) PRIMARY KEY,
 request_id VARCHAR(80) NOT NULL UNIQUE,
 sku_id VARCHAR(36) NOT NULL,
 bucket_id INT NOT NULL,
 quantity INT NOT NULL,
 released BOOLEAN NOT NULL DEFAULT FALSE,
 KEY idx_bucket_allocation(sku_id,bucket_id), CHECK(quantity>0)
);
