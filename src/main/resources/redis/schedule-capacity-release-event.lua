-- 좌석 반환 이벤트 적용 (Outbox 소비자): 같은 이벤트를 두 번 반영하지 않는다
-- 중계기 · Kafka 소비자는 같은 이벤트를 다시 받을 수 있으므로(처리됨을 남기기 전 재시작, Kafka 재전달), 이벤트마다 적용 표시를 남겨 거른다
-- KEYS[1] = 적용 표시 키, KEYS[2] = 적용한 이벤트 수 키, KEYS[3..n] = 회차별 사용량 키
-- ARGV[1] = 적용 표시 유효 시간(초), ARGV[3..n] = 회차별 돌려줄 인원 (KEYS와 같은 순서, ARGV[2]는 비움)
-- 적용 표시 값 = 반영한 시각(Redis 시계, ms): 전달 지연(생성 → 반영) 측정용
-- 반환: 1 = 반영, 0 = 이미 반영된 이벤트
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if not redis.call('SET', KEYS[1], nowMs, 'NX', 'EX', ARGV[1]) then return 0 end
redis.call('INCR', KEYS[2])
for i = 3, #KEYS do
  local current = redis.call('GET', KEYS[i])
  -- 사용량 키가 없으면 되살리지 않는다 (만료된 키를 TTL 없이 만들지 않음. 비어 있는 키는 재동기화가 채운다)
  if current then
    local newVal = tonumber(current) - tonumber(ARGV[i])
    if newVal < 0 then newVal = 0 end
    redis.call('SET', KEYS[i], newVal, 'KEEPTTL')
  end
end
return 1
