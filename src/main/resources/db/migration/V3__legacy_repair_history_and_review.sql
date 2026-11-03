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
