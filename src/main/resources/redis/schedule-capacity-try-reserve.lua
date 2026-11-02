-- 정원 선점: 같은 회원의 진행 중 예약 확인 · 정원 확인 · 사용량 증가를 한 번에 처리한다 (원자적)
-- KEYS[1] = 정원 키, KEYS[2] = 사용량 키, KEYS[3] = 회원 진행 중 표시 키
-- ARGV[1] = 요청 인원, ARGV[2] = 진행 중 표시 유효 시간(ms)
-- 반환: 2 = 같은 회원의 예약이 진행 중(사용량을 건드리지 않음), -1 = 정원 키 없음(필터를 건너뜀),
--       0 = 정원 초과(사용량을 되돌림), 1 = 선점 성공
-- 진행 중 표시: 같은 회원의 동시 요청이 앞 요청의 커밋 전에 DB 중복 확인을 통과해 자리를 잡고 있으면
--   나머지 요청이 매진으로 거절된다. 자리를 잡기 전에 막아 중복 요청이 정원을 쓰지 않게 한다.
--   성공하면 지우지 않고 유효 시간이 지나 사라지게 둔다 (그 뒤의 중복은 DB 확인이 막는다)
if redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
local max = redis.call('GET', KEYS[1])
if max == false then
  redis.call('SET', KEYS[3], '1', 'PX', ARGV[2])
  return -1
end
local newUsed = redis.call('INCRBY', KEYS[2], ARGV[1])
if newUsed > tonumber(max) then
  redis.call('DECRBY', KEYS[2], ARGV[1])
  return 0
end
redis.call('SET', KEYS[3], '1', 'PX', ARGV[2])
return 1
