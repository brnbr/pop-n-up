-- 정원 복구: 사용량을 줄이되 0 아래로 내려가지 않게 한다
-- KEYS[1] = 사용량 키, ARGV[1] = 되돌릴 인원
-- 반환: 복구 후 사용량, 키가 없으면 -1 (만료된 키를 TTL 없이 되살리지 않는다. 비어 있는 키는 재동기화가 채운다)
local current = redis.call('GET', KEYS[1])
if current == false then return -1 end
local newVal = tonumber(current) - tonumber(ARGV[1])
if newVal < 0 then newVal = 0 end
-- KEEPTTL: 값만 바꾸고 남은 만료 시간은 유지한다 (그냥 SET하면 TTL이 지워져 영구 키가 된다)
redis.call('SET', KEYS[1], newVal, 'KEEPTTL')
return newVal
