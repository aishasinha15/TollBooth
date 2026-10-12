-- Token bucket, run INSIDE Redis as one atomic step.
--
-- Redis runs a script start to finish without running any other command in between.
-- So two requests from the same user arriving at the same instant can never both
-- read "1 token left" and both get through: one runs fully, then the other.
--
-- KEYS[1] = this user's bucket key, e.g. "tb:bucket:1:user-42"
-- ARGV[1] = capacity           (max tokens, e.g. 5)
-- ARGV[2] = refill interval ms (time to earn back 1 token, e.g. 12000)
--
-- Returns { allowed (1 or 0), tokens left (rounded down), ms until next token if rejected }

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local interval_ms = tonumber(ARGV[2])

-- Use Redis's own clock, so every Tollbooth server agrees on "now"
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- Read the bucket. A new user starts with a full bucket.
local saved = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(saved[1])
local last = tonumber(saved[2])
if tokens == nil or last == nil then
  tokens = capacity
  last = now
end

-- Refill: add tokens for the time that passed, never above capacity
local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed / interval_ms)

-- Take one token if there is one
local allowed = 0
local retry_after_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after_ms = math.ceil((1 - tokens) * interval_ms)
end

-- Save, and let idle buckets delete themselves once they would be full again
redis.call('HSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, math.ceil(capacity * interval_ms) + 1000)

return { allowed, math.floor(tokens), retry_after_ms }
