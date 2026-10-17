import test from 'node:test'
import assert from 'node:assert/strict'
import {
  emptySchedule,
  travelDays,
  scheduleError,
  schedulePayload,
  dayDate,
  scheduleLabel,
} from '../src/utils/tripSchedule.js'
const schedule = () => ({
  ...emptySchedule(),
  departureLocation: ' 上海 ',
  destination: '杭州',
  departureDate: '2028-02-28',
  returnDate: '2028-03-01',
})
test('日期按自然日计算，跨月、跨年、闰年和同日往返不受时区影响', () => {
  assert.equal(travelDays(schedule()), 3)
  assert.equal(
    travelDays({ ...schedule(), departureDate: '2027-12-31', returnDate: '2028-01-01' }),
    2,
  )
  assert.equal(travelDays({ ...schedule(), returnDate: '2028-02-28' }), 1)
  assert.equal(dayDate('2028-02-28', 2), '2028-02-29')
})
test('无效日期、倒置日期和同日倒置时段不能提交', () => {
  assert.equal(travelDays({ ...schedule(), departureDate: '2028-02-30' }), 0)
  assert.ok(scheduleError({ ...schedule(), returnDate: '2028-02-27' }))
  assert.ok(
    scheduleError({
      ...schedule(),
      returnDate: '2028-02-28',
      departurePeriod: 'evening',
      returnPeriod: 'morning',
    }),
  )
  assert.equal(
    scheduleError({ ...schedule(), returnDate: '2028-02-28', departurePeriod: 'evening' }),
    '',
  )
  assert.ok(scheduleError({ ...schedule(), departureLocation: ' ' }))
  assert.ok(scheduleError({ ...schedule(), departurePeriod: 'night' }))
})
test('提交完整路线日期但不提交可篡改的天数，旧记录日期缺失保持待完善', () => {
  const payload = schedulePayload({ ...schedule(), travelDays: 99 })
  assert.equal(payload.departureLocation, '上海')
  assert.equal(payload.departureDate, '2028-02-28')
  assert.equal(payload.returnPeriod, 'flexible')
  assert.equal('travelDays' in payload, false)
  assert.equal(scheduleLabel({}), '出行日期待完善')
})
