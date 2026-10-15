<script setup>
import { useId } from 'vue'
import { activities, paceNames } from '../utils/format.js'
const pace = defineModel('pace', { type: String })
const selected = defineModel('activities', { type: Array, default: () => [] })
const id = useId()
</script>
<template>
  <div>
    <label class="label" :for="id">你偏好的旅行节奏</label
    ><select :id="id" v-model="pace" class="field" required>
      <option v-for="(name, value) in paceNames" :key="value" :value="value">{{ name }}</option>
    </select>
  </div>
  <fieldset class="profile-interests">
    <legend class="label">你最喜欢的活动 <span class="tiny muted">（至少选择一项）</span></legend>
    <div class="interest-options">
      <label v-for="item in [...new Set([...activities, ...selected])]" :key="item"
        ><input v-model="selected" type="checkbox" :value="item" /><span>{{ item }}</span></label
      >
    </div>
  </fieldset>
</template>
