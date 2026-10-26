-- Demo data, loaded only with the "demo" profile (see application-demo.yml).
-- Repeatable migration: Flyway runs it after all versioned migrations, so adding V2, V3...
-- later never conflicts with it. Idempotent thanks to ON CONFLICT DO NOTHING.
INSERT INTO rooms (name, location, capacity) VALUES
    ('Sao Mai',    'Floor 3', 4),
    ('Binh Minh',  'Floor 3', 6),
    ('Hoang Hon',  'Floor 3', 8),
    ('Ha Long',    'Floor 4', 10),
    ('Phu Quoc',   'Floor 4', 6),
    ('Da Lat',     'Floor 4', 4),
    ('Sa Pa',      'Floor 5', 12),
    ('Hoi An',     'Floor 5', 8),
    ('Con Dao',    'Floor 5', 20),
    ('Mekong',     'Floor 6', 30)
ON CONFLICT (name) DO NOTHING;

INSERT INTO room_equipment (room_id, equipment)
SELECT r.id, e.equipment
FROM rooms r
JOIN (VALUES
    ('Sao Mai',   'WHITEBOARD'),
    ('Binh Minh', 'TV'),
    ('Binh Minh', 'WHITEBOARD'),
    ('Hoang Hon', 'PROJECTOR'),
    ('Hoang Hon', 'WHITEBOARD'),
    ('Ha Long',   'PROJECTOR'),
    ('Ha Long',   'VIDEO_CONFERENCE'),
    ('Phu Quoc',  'TV'),
    ('Phu Quoc',  'VIDEO_CONFERENCE'),
    ('Da Lat',    'TV'),
    ('Sa Pa',     'PROJECTOR'),
    ('Sa Pa',     'VIDEO_CONFERENCE'),
    ('Sa Pa',     'WHITEBOARD'),
    ('Hoi An',    'PROJECTOR'),
    ('Con Dao',   'PROJECTOR'),
    ('Con Dao',   'VIDEO_CONFERENCE'),
    ('Mekong',    'PROJECTOR'),
    ('Mekong',    'VIDEO_CONFERENCE'),
    ('Mekong',    'WHITEBOARD')
) AS e(room_name, equipment) ON e.room_name = r.name
ON CONFLICT DO NOTHING;
