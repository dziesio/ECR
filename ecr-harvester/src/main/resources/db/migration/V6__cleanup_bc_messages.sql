-- Remove BC messages saved before stable UUID-based IDs were introduced.
-- These have IDs starting with 'bc_' but not matching 'bc_<uuid>' format,
-- or were saved with empty content (the broken early scraping attempts).
DELETE FROM messages
WHERE message_source = 'BRITISH_COUNCIL'
  AND (
    content IS NULL
    OR content = ''
    OR librus_message_id NOT SIMILAR TO 'bc_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
  );
