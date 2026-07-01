-- Mock AIRBOX_INSTALLATIONS rows for local/dev use with mock-firmware/publish_random.py.
INSERT INTO AIRBOX_INSTALLATIONS (device_id, apikey, owner_email, co_owner1_email, co_owner2_email, installation, notes)
VALUES
    ('airbox-mock-001', 'mockkey-3f9a1c2b8e7d4056', 'alice.owner@example.com', NULL,                       NULL,                       'indoor',  'Mock device — full sensor suite'),
    ('airbox-mock-002', 'mockkey-7b2e5f0a9c1d3468', 'bob.owner@example.com',   'bob.cowner@example.com',   NULL,                       'outdoor', 'Mock device — SEN66 without raw VOC/NOX'),
    ('airbox-mock-003', 'mockkey-a1c4d8e2f6b09357', 'carol.owner@example.com', NULL,                       NULL,                       'indoor',  'Mock device — SCD30, CO2 only'),
    ('airbox-mock-004', 'mockkey-9d0f3a7c1e5b2864', 'dave.owner@example.com',  'dave.cowner1@example.com', 'dave.cowner2@example.com', 'outdoor', 'Mock device — full sensor suite')
ON CONFLICT (device_id) DO NOTHING;
