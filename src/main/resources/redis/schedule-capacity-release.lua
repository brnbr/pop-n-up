-- 정원 복구: 사용량을 줄이되 0 아래로 내려가지 않게 한다
-- KEYS[1] = 사용량 키, ARGV[1] = 되돌릴 인원
-- 반환: 복구 후 사용량
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local newVal = current - tonumber(ARGV[1])
if newVal < 0 then newVal = 0 end
redis.call('SET', KEYS[1], newVal)
return newVal
