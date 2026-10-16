-- 정원 선점: 정원 확인과 사용량 증가를 한 번에 처리한다 (원자적)
-- KEYS[1] = 정원 키, KEYS[2] = 사용량 키, ARGV[1] = 요청 인원
-- 반환: -1 = 정원 키 없음(필터를 건너뜀), 0 = 정원 초과(사용량을 되돌림), 1 = 선점 성공
local max = redis.call('GET', KEYS[1])
if max == false then return -1 end
local newUsed = redis.call('INCRBY', KEYS[2], ARGV[1])
if newUsed > tonumber(max) then
  redis.call('DECRBY', KEYS[2], ARGV[1])
  return 0
end
return 1
