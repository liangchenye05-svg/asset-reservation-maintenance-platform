-- Equipment Management: structure only, no seed accounts or business rows.
-- Verified against local MySQL metadata on 2026-10-16 (Asia/Shanghai).
-- Run in a NEW EMPTY database selected by the operator. Existing tables cause an error.
-- No CREATE DATABASE, USE, DROP, INSERT, credentials, or live AUTO_INCREMENT counters.
SET NAMES utf8mb4;

CREATE TABLE `users` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` varchar(20) NOT NULL DEFAULT 'USER',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `device` (
  `id` int NOT NULL AUTO_INCREMENT,
  `device_code` varchar(50) NOT NULL,
  `name` varchar(50) NOT NULL,
  `status` varchar(50) NOT NULL DEFAULT 'AVAILABLE',
  `location` varchar(100) NOT NULL,
  `model` varchar(50) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `device_code` (`device_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reservation` (
  `id` int NOT NULL AUTO_INCREMENT,
  `device_id` int NOT NULL,
  `user_id` int NOT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `device_id` (`device_id`),
  CONSTRAINT `reservation_ibfk_1` FOREIGN KEY (`device_id`) REFERENCES `device` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `repair_order` (
  `id` int NOT NULL AUTO_INCREMENT,
  `device_id` int NOT NULL,
  `user_id` int NOT NULL,
  `description` varchar(500) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `device_id` (`device_id`),
  KEY `user_id` (`user_id`),
  CONSTRAINT `repair_order_ibfk_1` FOREIGN KEY (`device_id`) REFERENCES `device` (`id`),
  CONSTRAINT `repair_order_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Current structure through V2; manual new-empty-test-database compatibility.
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

-- Current empty-database structure through V3. New empty database only.
-- Additive metadata only. Never invent old operations or restore device state.
ALTER TABLE repair_order ADD COLUMN history_complete tinyint(1) NOT NULL DEFAULT 0;
ALTER TABLE device ADD COLUMN maintenance_restore_blocked tinyint(1) NOT NULL DEFAULT 0;
ALTER TABLE repair_order_history ADD COLUMN review_note varchar(500) DEFAULT NULL;

-- Classify only a complete existing state-transition chain; never fill missing events.
-- Preserve old business timestamps when updating metadata.
UPDATE repair_order r SET history_complete=1,update_at=update_at
WHERE (SELECT COUNT(*) FROM repair_order_history all_events WHERE all_events.repair_order_id=r.id)
 = CASE r.status WHEN 'PENDING' THEN 1 WHEN 'PROCESSING' THEN 2 WHEN 'COMPLETED' THEN 3 ELSE 0 END
AND EXISTS (SELECT 1 FROM repair_order_history c WHERE c.repair_order_id=r.id
 AND c.action='CREATED' AND c.from_status IS NULL AND c.to_status='PENDING' AND c.actor_id=r.user_id
 AND (r.status='PENDING' OR (r.status='PROCESSING' AND EXISTS (
 SELECT 1 FROM repair_order_history p WHERE p.repair_order_id=r.id AND p.id>c.id
 AND p.action='PROCESSED' AND p.from_status='PENDING' AND p.to_status='PROCESSING'))
 OR (r.status='COMPLETED' AND EXISTS (SELECT 1 FROM repair_order_history p
 JOIN repair_order_history f ON f.repair_order_id=p.repair_order_id AND f.id>p.id
 AND f.action='COMPLETED' AND f.from_status='PROCESSING' AND f.to_status='COMPLETED'
 WHERE p.repair_order_id=r.id AND p.id>c.id AND p.action='PROCESSED'
 AND p.from_status='PENDING' AND p.to_status='PROCESSING'))));

-- A later sibling must not turn an uncertain legacy device into an auto-restorable one.
UPDATE device d SET maintenance_restore_blocked=1
WHERE EXISTS (SELECT 1 FROM repair_order r WHERE r.device_id=d.id AND r.status='PROCESSING'
 AND (d.status_before_maintenance IS NULL
 OR NOT EXISTS (SELECT 1 FROM repair_order_history h WHERE h.repair_order_id=r.id
 AND h.action='PROCESSED' AND h.from_status='PENDING' AND h.to_status='PROCESSING')));
