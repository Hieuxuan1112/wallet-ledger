-- token_hash was declared CHAR(64) in V1. PostgreSQL blank-pads CHAR(n) and ignores trailing
-- spaces when comparing, which is the wrong contract for an exact-match lookup on a security
-- token, and PostgreSQL's own documentation recommends against char(n) generally.
-- V1 is already committed, so it is not edited; the column is altered here instead.
ALTER TABLE refresh_token ALTER COLUMN token_hash TYPE VARCHAR(64);
