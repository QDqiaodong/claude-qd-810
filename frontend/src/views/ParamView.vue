<template>
  <div class="dc-page">
    <div v-if="fromHandover" class="return-bar">
      <span>正在核查钻孔：<b>{{ currentHole?.holeNo || boreholeId }}</b> 参数，处理后返回交接台。</span>
      <el-button size="small" type="primary" @click="backToHandover">返回交接台</el-button>
    </div>
    <div class="dc-toolbar">
      <span class="dc-label">钻孔</span>
      <el-select v-model="boreholeId" size="small" style="width: 190px" clearable @change="loadSeriesList">
        <el-option v-for="h in holes" :key="h.id" :label="h.holeNo" :value="h.id" />
      </el-select>
      <span class="dc-label">钻进时序</span>
      <el-select v-model="seriesId" size="small" style="width: 300px" @change="loadAnalysis">
        <el-option
          v-for="s in seriesList"
          :key="s.id"
          :label="'#' + s.id + ' · ' + s.recordDate + ' ' + s.shiftType + '班 · ' + (s.operator || '—')"
          :value="s.id"
        />
      </el-select>
      <span class="dc-label">孔号 {{ data.holeNo || '—' }}</span>
      <span class="dc-label">采样 {{ data.pointCount || 0 }} 点</span>
      <span style="color: #c05621; font-size: 12px">异常 {{ data.abnormalCount || 0 }} 点</span>
      <el-button v-if="data.seriesId" size="small" type="danger" :disabled="readonly" @click="removeSeries">删除异常时序后整改</el-button>
      <el-tag v-if="lockText" type="danger">{{ lockText }}</el-tag>
      <div style="width: 240px">
        <el-slider v-model="rangeArr" range :min="0" :max="Math.max(pointCount - 1, 0)" :marks="{ }" size="small" />
      </div>
      <span class="dc-label">区间 {{ rangeArr[0] }} ~ {{ rangeArr[1] }}</span>
    </div>

    <div class="dc-panel">
      <div class="dc-panel-title">钻进参数时序带（钻压 / 转速 / 泵量，异常点打三角标）</div>
      <div style="overflow-x: auto">
        <svg :viewBox="`0 0 ${svgWidth} 430`" class="dc-svg" :style="{ width: svgWidth + 'px', maxWidth: '100%' }">
          <!-- 三条带 -->
          <g v-for="band in bands" :key="band.key">
            <rect x="46" :y="band.top" :width="svgWidth - 70" :height="110" fill="#fffdf7" stroke="#ddcfb4" />
            <text x="8" :y="band.top + 16" font-size="11" fill="#5d4037" font-weight="700">{{ band.label }}</text>
            <text x="8" :y="band.top + 32" font-size="10" fill="#8a7761">{{ band.unit }}</text>

            <line
              v-for="g in 4"
              :key="g"
              x1="46"
              :y1="band.top + (110 / 5) * g"
              :x2="svgWidth - 24"
              :y2="band.top + (110 / 5) * g"
              stroke="#efe6d5"
            />

            <polyline
              :points="band.points"
              fill="none"
              :stroke="band.color"
              stroke-width="2"
            />

            <g v-for="p in band.dots" :key="p.index">
              <circle :cx="p.x" :cy="p.y" r="3" :fill="band.color" />
              <polygon
                v-if="p.abnormal"
                :points="`${p.x},${p.y - 9} ${p.x - 5},${p.y - 17} ${p.x + 5},${p.y - 17}`"
                fill="#c05621"
              >
                <title>{{ p.time }} 异常：{{ (p.abnormalItems || []).join('、') }}｜孔内：{{ p.holeNote || '—' }}</title>
              </polygon>
              <circle :cx="p.x" :cy="p.y" r="9" fill="transparent" style="cursor: pointer" @click="selectPoint(p.raw)">
                <title>{{ p.time }} ｜ {{ band.label }} {{ p.value }}{{ band.unit }}｜孔内：{{ p.holeNote || '—' }}</title>
              </circle>
            </g>

            <text
              v-for="p in band.dots"
              :key="'t' + p.index"
              :x="p.x"
              :y="band.top + 124"
              text-anchor="middle"
              font-size="9"
              fill="#8a7761"
            >
              {{ p.time }}
            </text>
          </g>
        </svg>
      </div>

      <div style="margin-top: 10px; font-size: 11px; color: #8a7761">
        合理区间：钻压 {{ rng('pressure') }} kN · 转速 {{ rng('rotation') }} rpm · 泵量 {{ rng('pump') }} L/min · 泥浆密度 {{ rng('mudDensity') }} g/cm³
        ｜ 三角标 = 越限点，鼠标悬浮看孔内情况备注
      </div>
    </div>

    <div class="dc-panel">
      <div class="dc-panel-title">异常点清单</div>
      <div v-for="p in abnormalPoints" :key="p.index" class="dc-warn" @click="selectPoint(p)">
        <span style="width: 62px; font-weight: 700">{{ p.time }}</span>
        <span style="width: 148px">钻压 {{ num(p.pressure) }} kN</span>
        <span style="width: 148px">转速 {{ num(p.rotation) }} rpm</span>
        <span style="width: 148px">泵量 {{ num(p.pump) }} L/min</span>
        <span style="color: #c05621">{{ (p.abnormalItems || []).join('、') }}</span>
      </div>
      <div v-if="!abnormalPoints.length" style="font-size: 12px; color: #8a7761">该区间无异常点</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setDetail } from '../detail'
import { navigation, returnToHandover } from '../navigation'

const holes = ref([])
const boreholeId = ref(null)
const handoverInfo = ref(null)
const seriesList = ref([])
const seriesId = ref(null)
const data = ref({})
const rangeArr = ref([0, 0])

const currentHole = computed(() => holes.value.find((h) => h.id === boreholeId.value) || null)
const fromHandover = computed(() => navigation.returnTab === 'handover' && !!navigation.handoverBoreholeId)
const readonly = computed(() => handoverInfo.value?.status === '待检查' || handoverInfo.value?.status === '已接收')
const lockText = computed(() => {
  if (handoverInfo.value?.status === '待检查') return '待检查快照已锁定'
  if (handoverInfo.value?.status === '已接收') return '已接收资料已锁定，需返开'
  return ''
})
const pointCount = computed(() => (data.value.points || []).length)
const visiblePoints = computed(() => (data.value.points || []).slice(rangeArr.value[0], rangeArr.value[1] + 1))
const abnormalPoints = computed(() => visiblePoints.value.filter((p) => p.abnormal))

const svgWidth = computed(() => Math.max(visiblePoints.value.length * 42, 700))

function num(v) {
  return v == null ? '—' : Number(v)
}

function rng(key) {
  const r = (data.value.ranges || {})[key]
  return r ? num(r[0]) + ' ~ ' + num(r[1]) : '—'
}

function scaleBand(values, top) {
  const nums = values.map((v) => Number(v)).filter((v) => !Number.isNaN(v))
  const min = Math.min(...nums, 0)
  const max = Math.max(...nums, 1)
  return (v) => top + 100 - ((Number(v) - min) / (max - min || 1)) * 90
}

function xAt(i) {
  const n = Math.max(visiblePoints.value.length - 1, 1)
  return 56 + (i / n) * (svgWidth.value - 96)
}

const bands = computed(() => {
  const pts = visiblePoints.value
  if (!pts.length) return []
  const defs = [
    { key: 'pressure', label: '钻压', unit: 'kN', color: '#c05621' },
    { key: 'rotation', label: '转速', unit: 'rpm', color: '#4a6572' },
    { key: 'pump', label: '泵量', unit: 'L/min', color: '#5d4037' }
  ]
  return defs.map((d, i) => {
    const top = 16 + i * 138
    const scale = scaleBand(pts.map((p) => p[d.key]), top)
    const dots = pts.map((p, idx) => ({
      index: p.index,
      time: p.time,
      value: p[d.key],
      abnormal: p.abnormal,
      abnormalItems: p.abnormalItems,
      holeNote: p.holeNote,
      raw: p,
      x: xAt(idx),
      y: scale(p[d.key])
    }))
    return { ...d, top, dots, points: dots.map((p) => `${p.x},${p.y}`).join(' ') }
  })
})

function selectPoint(p) {
  setDetail({
    title: '采样点 ' + (p.time || '—'),
    subtitle: '孔号 ' + (data.value.holeNo || '—') + ' · ' + (data.value.recordDate || '') + ' ' + (data.value.shiftType || '') + '班',
    tag: p.abnormal ? '异常点' : '正常点',
    fields: [
      { label: '采样时刻', value: p.time || '—' },
      { label: '钻压 (kN)', value: num(p.pressure) },
      { label: '转速 (rpm)', value: num(p.rotation) },
      { label: '泵量 (L/min)', value: num(p.pump) },
      { label: '泥浆密度 (g/cm³)', value: num(p.mudDensity) },
      { label: '孔内情况', value: p.holeNote || '—' },
      { label: '越限项', value: (p.abnormalItems || []).join('、') || '无' }
    ]
  })
}

watch(pointCount, (n) => {
  rangeArr.value = [0, Math.max(n - 1, 0)]
})

async function loadAnalysis() {
  data.value = await api.analysis(seriesId.value)
  const n = (data.value.points || []).length
  rangeArr.value = [0, Math.max(n - 1, 0)]
}

async function loadSeriesList() {
  seriesList.value = await api.series(boreholeId.value || undefined)
  const targetId = navigation.query?.seriesId
  seriesId.value = targetId && seriesList.value.some((s) => s.id === targetId)
    ? targetId
    : seriesList.value[0]?.id || null
  if (seriesId.value) await loadAnalysis()
  else data.value = {}
}

async function loadHandoverInfo() {
  if (!boreholeId.value) {
    handoverInfo.value = null
    return
  }
  try {
    const hd = await api.handoverDetail(boreholeId.value)
    handoverInfo.value = hd.active
  } catch (e) {
    handoverInfo.value = null
  }
}

async function removeSeries() {
  await api.deleteSeries(seriesId.value)
  ElMessage.success('参数时序已删除，整改后请重新提交交接')
  await loadSeriesList()
}

function backToHandover() {
  returnToHandover()
}

watch(boreholeId, async () => {
  await loadSeriesList()
  await loadHandoverInfo()
})

onMounted(async () => {
  holes.value = await api.boreholes()
  if (navigation.handoverBoreholeId) boreholeId.value = navigation.handoverBoreholeId
  else if (navigation.boreholeId) boreholeId.value = navigation.boreholeId
  else if ((navigation.query || {}).boreholeId) boreholeId.value = navigation.query.boreholeId
  await loadSeriesList()
  await loadHandoverInfo()
  navigation.query = {}
})
</script>
