export const periods = [
  { value: 'flexible', label: '不限时段' },
  { value: 'morning', label: '上午' },
  { value: 'afternoon', label: '下午' },
  { value: 'evening', label: '晚上' },
]
export const emptySchedule = () => ({
  departureLocation: '',
  destination: '',
  departureDate: '',
  departurePeriod: 'flexible',
  returnDate: '',
  returnPeriod: 'flexible',
})

function dateValue(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return NaN
  const timestamp = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value
    ? timestamp
    : NaN
}
export function travelDays(schedule) {
  const days = (dateValue(schedule.returnDate) - dateValue(schedule.departureDate)) / 86400000 + 1
  return Number.isInteger(days) && days > 0 && days <= 65535 ? days : 0
}
export function scheduleError(schedule) {
  if (!schedule.departureLocation?.trim() || !schedule.destination?.trim())
    return '请填写出发地和目的地'
  if (schedule.departureLocation.trim().length > 120 || schedule.destination.trim().length > 120)
    return '出发地和目的地不能超过120个字符'
  if (!schedule.departureDate || !schedule.returnDate) return '请选择出发日期和返回日期'
  if (!travelDays(schedule) || schedule.departureDate < '1000-01-01')
    return '请填写有效日期，返回日期不能早于出发日期，最多65535天'
  const order = ['morning', 'afternoon', 'evening']
  if (
    ![schedule.departurePeriod, schedule.returnPeriod].every((value) =>
      periods.some((p) => p.value === value),
    )
  )
    return '请选择有效的出发和返回时段'
  if (
    schedule.departureDate === schedule.returnDate &&
    order.includes(schedule.departurePeriod) &&
    order.includes(schedule.returnPeriod) &&
    order.indexOf(schedule.returnPeriod) < order.indexOf(schedule.departurePeriod)
  )
    return '同日往返时，返回时段不能早于出发时段'
  return ''
}
export function schedulePayload(schedule) {
  return {
    departureLocation: schedule.departureLocation.trim(),
    destination: schedule.destination.trim(),
    departureDate: schedule.departureDate,
    departurePeriod: schedule.departurePeriod,
    returnDate: schedule.returnDate,
    returnPeriod: schedule.returnPeriod,
  }
}
export function dayDate(departureDate, dayNumber) {
  const timestamp = dateValue(departureDate)
  return Number.isFinite(timestamp)
    ? new Date(timestamp + (Number(dayNumber) - 1) * 86400000).toISOString().slice(0, 10)
    : ''
}
export function scheduleLabel(trip) {
  if (!trip?.departureDate || !trip?.returnDate) return '出行日期待完善'
  const label = (value) => periods.find((p) => p.value === value)?.label || '时段待完善'
  return `${trip.departureDate} ${label(trip.departurePeriod)} — ${trip.returnDate} ${label(trip.returnPeriod)}`
}
export function routeLabel(trip) {
  return trip?.departureLocation
    ? `${trip.departureLocation} → ${trip.destination}`
    : trip?.destination || ''
}
