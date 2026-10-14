-- Reserve money from an app's daily AI budget BEFORE calling the AI, atomically.
--
-- Why reserve first? The real cost is only known after the AI answers.
-- If we only checked "spent < budget", 50 questions arriving together could all
-- pass the check and overshoot. Like a hotel pre-authorising your card,
-- we hold an estimate now and settle the exact amount afterwards.
--
-- All money is in MICRO-dollars (1 USD = 1,000,000) as whole numbers:
-- integers never have rounding errors, and Redis can add them atomically.
--
-- KEYS[1] = today's spend counter, e.g. "tb:spend:1:2026-10-12"
-- ARGV[1] = daily budget            ARGV[2] = hard stop limit
-- ARGV[3] = wants smart model (1/0) ARGV[4] = smart model cost estimate
-- ARGV[5] = cheap model cost estimate
-- ARGV[6] = expiry in seconds (old days clean themselves up)
--
-- Returns { allowed (1/0), smart allowed (1/0), amount reserved, total spent incl. this reservation }

local spent = tonumber(redis.call('GET', KEYS[1]) or '0')
local budget = tonumber(ARGV[1])
local hard_limit = tonumber(ARGV[2])
local wants_smart = tonumber(ARGV[3])
local smart_estimate = tonumber(ARGV[4])
local cheap_estimate = tonumber(ARGV[5])

-- Phase 7: the estimates are WORST cases (the longest answer allowed), so if even the
-- cheap model's worst case would cross the hard stop, refuse. Spending can then never pass it.
-- (Phase 5 checked "spent >= hard_limit", which let one long answer push past: the 267% bug.)
if spent + cheap_estimate > hard_limit then
  return { 0, 0, 0, spent }
end

-- The smart model only while it still fits inside the budget; otherwise the cheap one
local use_smart = 0
local estimate = cheap_estimate
if wants_smart == 1 and spent + smart_estimate <= budget then
  use_smart = 1
  estimate = smart_estimate
end

local total = redis.call('INCRBY', KEYS[1], estimate)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[6]))
return { 1, use_smart, estimate, total }
