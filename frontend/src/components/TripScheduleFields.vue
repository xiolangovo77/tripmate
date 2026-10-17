<script setup>
import { computed } from 'vue'
import { periods, travelDays } from '../utils/tripSchedule.js'
const model = defineModel({ required: true })
defineProps({ prefix: { type: String, default: 'trip' }, disabled: Boolean })
const days = computed(() => travelDays(model.value))
</script>
<template>
  <fieldset class="schedule-fields" :disabled="disabled">
    <div>
      <label class="label" :for="`${prefix}-origin`">从哪里出发？</label>
      <input
        :id="`${prefix}-origin`"
        v-model="model.departureLocation"
        class="field"
        placeholder="出发城市"
        maxlength="120"
        required
      />
    </div>
    <div>
      <label class="label" :for="`${prefix}-city`">这次想去哪里？</label>
      <input
        :id="`${prefix}-city`"
        v-model="model.destination"
        class="field"
        placeholder="目的地城市"
        maxlength="120"
        required
      />
    </div>
    <div>
      <label class="label" :for="`${prefix}-departure-date`">出发日期</label>
      <input
        :id="`${prefix}-departure-date`"
        v-model="model.departureDate"
        type="date"
        min="1000-01-01"
        max="9999-12-31"
        class="field"
        required
      />
      <label class="sr-only" :for="`${prefix}-departure-period`">出发时段</label>
      <select
        :id="`${prefix}-departure-period`"
        v-model="model.departurePeriod"
        class="field period-field"
      >
        <option v-for="p in periods" :key="p.value" :value="p.value">{{ p.label }}</option>
      </select>
    </div>
    <div>
      <label class="label" :for="`${prefix}-return-date`">返回日期</label>
      <input
        :id="`${prefix}-return-date`"
        v-model="model.returnDate"
        type="date"
        :min="model.departureDate || '1000-01-01'"
        max="9999-12-31"
        class="field"
        required
      />
      <label class="sr-only" :for="`${prefix}-return-period`">返回时段</label>
      <select
        :id="`${prefix}-return-period`"
        v-model="model.returnPeriod"
        class="field period-field"
      >
        <option v-for="p in periods" :key="p.value" :value="p.value">{{ p.label }}</option>
      </select>
    </div>
    <p class="schedule-hint tiny muted" aria-live="polite">
      {{
        days ? `共 ${days} 天，含出发和返回当天。` : '选择日期后自动计算旅行天数。'
      }}返回日期指回到出发地的日期。
    </p>
  </fieldset>
</template>
<style scoped>
.schedule-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.schedule-fields > div {
  min-width: 0;
}
.period-field {
  margin-top: 8px;
}
.schedule-hint {
  grid-column: 1 / -1;
  margin: 0;
}
@media (max-width: 440px) {
  .schedule-fields {
    gap: 12px;
  }
}
</style>
