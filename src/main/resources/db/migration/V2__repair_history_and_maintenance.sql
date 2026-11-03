-- Additive migration. No historical repair actors/timestamps are invented.
ALTER TABLE device ADD COLUMN status_before_maintenance varchar(50) DEFAULT NULL;
CREATE TABLE repair_order_history (
 id int NOT NULL AUTO_INCREMENT,
 repair_order_id int NOT NULL,
 actor_id int NOT NULL,
 action varchar(20) NOT NULL,
 from_status varchar(20) DEFAULT NULL,
 to_status varchar(20) NOT NULL,
 created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (id),
 KEY idx_history_order_id (repair_order_id,id),
 CONSTRAINT fk_history_order FOREIGN KEY (repair_order_id) REFERENCES repair_order(id),
 CONSTRAINT fk_history_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_repair_owner_status_id ON repair_order(user_id,status,id);
CREATE INDEX idx_repair_status_id ON repair_order(status,id);
CREATE INDEX idx_repair_device_status_id ON repair_order(device_id,status,id);
CREATE INDEX idx_reservation_device_status_time ON reservation(device_id,status,start_time,end_time);
