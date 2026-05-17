-- V6 used an incorrect SIMILAR TO pattern that failed to preserve valid bc_news_{uuid} IDs.
-- Re-clean: delete any BC messages whose librus_message_id is not the stable bc_news_{uuid} format.
DELETE FROM messages
WHERE librus_message_id ~ '^bc_'
  AND librus_message_id !~ '^bc_news_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
