# 17TRACK fixtures

Hand-written from the 17TRACK API v2.4 documentation (https://api.17track.net/en/doc), with a
made-up tracking number and made-up scans. They contain no real parcel data and no API key.

The only value the adapter depends on is the error code `-18019902` (number not registered); other
error codes only appear in messages. When we make a real call with a key, replace these with the
recorded responses, scrubbed of real numbers and addresses, and keep the tests passing.
