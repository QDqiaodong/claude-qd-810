<template>
  <div class="dc-page">
    <div v-if="fromHandover" class="return-bar">
      <span>正在整改钻孔：<b>{{ currentHole?.holeNo || holeId }}</b>，处理后返回交接台保留检查进度。</span>
      <el-button size="small" type="primary" @click="backToHandover">返回交接台</el-button>
    </div>
    <div class="dc-toolbar">
      <span class="dc-label">钻孔</span>
      <el-select v-model="holeId" size="small" style="width: 220px" @change="loadAll">
        <el-option v-for="h in holes" :key="h.id" :label="h.holeNo" :value="h.id" />
      </el-select>
      <span class="dc-label">共 {{ layers.length }} 层 / 最深 {{ num(maxDepth) }} m</span>
      <el-button size="small" :type="readonly ? 'info' : 'primary'" :disabled="readonly" @click="openCreateLayer">补录岩性层</el-button>
      <el-tag v-if="lockText" type="danger">{{ lockText }}</el-tag>
    </div>

    <div class="dc-split">
      <div class="dc-panel" style="flex: none; width: 430px">
        <div class="dc-panel-title">
          岩芯柱状剖面（点层段看属性包）
          <el-button v-if="selectedLayer" size="small" style="float: right" :disabled="readonly" @click="openEditLayer">编辑层段</el-button>
        </div>
        <svg viewBox="0 0 380 660" class="dc-svg" style="width: 100%">
          <line x1="86" y1="16" x2="86" y2="640" stroke="#8d6e63" stroke-width="1" />
          <g v-for="t in ticks" :key="t">
            <line x1="78" :y1="depthY(t)" x2="86" :y2="depthY(t)" stroke="#8d6e63" stroke-width="1" />
            <text x="74" :y="depthY(t) + 4" text-anchor="end" font-size="10" fill="#8a7761">{{ t }}</text>
          </g>

          <g v-for="seg in segments" :key="seg.id" style="cursor: pointer" @click="selectLayer(seg)">
            <rect
              x="92"
              :y="depthY(seg.from)"
              width="128"
              :height="Math.max(depthY(seg.to) - depthY(seg.from), 3)"
              :fill="lithoColor(seg.lithology)"
              :stroke="seg.id === selectedId ? '#c05621' : '#6d5b48'"
              :stroke-width="seg.id === selectedId ? 3 : 0.8"
            />
            <text x="98" :y="depthY(seg.from) + 12" font-size="10" fill="#fff">{{ seg.lithology }}</text>
            <text x="228" :y="depthY(seg.from) + 12" font-size="10" fill="#5d4037">
              RQD {{ seg.rqd == null ? '—' : num(seg.rqd) }} · 采取率 {{ seg.recovery == null ? '—' : num(seg.recovery) }}%
            </text>
            <text x="228" :y="depthY(seg.from) + 24" font-size="10" fill="#8a7761">
              {{ num(seg.from) }} ~ {{ num(seg.to) }} m
            </text>
            <line x1="92" :y1="depthY(seg.to)" x2="220" :y2="depthY(seg.to)" stroke="#6d5b48" stroke-width="0.5" />
          </g>
        </svg>

        <div class="dc-legend">
          <div v-for="(c, name) in colorMap" :key="name" class="dc-legend-item">
            <span class="dc-legend-swatch" :style="{ background: c }"></span>{{ name }}
          </div>
        </div>
      </div>

      <div style="flex: 1; min-width: 320px; display: flex; flex-direction: column; gap: 12px">
        <div class="dc-panel">
          <div class="dc-panel-title">岩性厚度占比</div>
          <div class="dc-stack">
            <div
              v-for="s in lithoStats"
              :key="s.lithology"
              class="dc-stack-seg"
              :style="{ width: num(s.ratio) + '%', background: lithoColor(s.lithology) }"
              :title="s.lithology + ' ' + num(s.ratio) + '%'"
            >
              {{ num(s.ratio) >= 6 ? s.lithology + ' ' + Math.round(num(s.ratio)) + '%' : '' }}
            </div>
          </div>
          <div style="margin-top: 8px; font-size: 11px; color: #8a7761">
            <span v-for="s in lithoStats" :key="s.lithology" style="margin-right: 12px">
              {{ s.lithology }}：{{ s.layerCount }} 层 / {{ num(s.totalThickness) }} m
            </span>
          </div>
        </div>

        <div class="dc-panel">
          <div class="dc-panel-title">
          钻进回次
          <el-button size="small" style="float: right; margin-left: 6px" :disabled="readonly" @click="openCreateRound">新增回次</el-button>
          <el-button v-if="selectedRound" size="small" style="float: right" :disabled="readonly" @click="openEditRound">编辑</el-button>
        </div>
          <div v-for="r in rounds" :key="r.id" class="dc-round-row" @click="selectRound(r)">
            <span style="width: 62px; font-weight: 700">第 {{ r.roundNo }} 回次</span>
            <span style="width: 130px">{{ num(r.fromDepth) }} ~ {{ num(r.toDepth) }} m</span>
            <span style="width: 108px">岩芯 {{ num(r.coreLength) }} m</span>
            <span style="width: 108px">采取率 {{ num(r.recoveryRate) }}%</span>
            <span style="width: 78px">RQD {{ num(r.rqd) }}</span>
            <span style="color: #8a7761">{{ r.driller || '—' }} · {{ r.sampledAt || '' }}</span>
            <span v-if="r.lowRecoveryReason" style="color: #c05621; font-size: 11px">{{ r.lowRecoveryReason }}</span>
          </div>
        </div>

        <div class="dc-panel">
          <div class="dc-panel-title">低采取率预警（编录质量核查）</div>
          <div class="dc-warn-list">
            <div v-for="r in lowList" :key="r.id" class="dc-warn" @click="holeId = r.boreholeId; loadAll()">
              <span style="width: 96px; font-weight: 700">孔 {{ r.boreholeId }}</span>
              <span style="width: 60px">第 {{ r.roundNo }} 回次</span>
              <span style="width: 96px">采取率 {{ num(r.recoveryRate) }}%</span>
              <span style="color: #c05621">{{ r.lowRecoveryReason || '未填原因' }}</span>
            </div>
            <div v-if="!lowList.length" style="font-size: 12px; color: #8a7761">无低采取率回次</div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="layerDialog" :title="layerForm.id ? '编辑岩性层' : '补录岩性层'" width="460px">
      <el-form :model="layerForm" label-width="96px" size="small">
        <el-form-item label="所属回次">
          <el-select v-model="layerForm.roundId" style="width: 100%">
            <el-option v-for="r in rounds" :key="r.id" :label="'第 ' + r.roundNo + ' 回次'" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="层序"><el-input-number v-model="layerForm.layerSeq" /></el-form-item>
        <el-form-item label="岩性"><el-input v-model="layerForm.lithology" /></el-form-item>
        <el-form-item label="起始深度 m"><el-input-number v-model="layerForm.fromDepth" :precision="2" /></el-form-item>
        <el-form-item label="终止深度 m"><el-input-number v-model="layerForm.toDepth" :precision="2" /></el-form-item>
        <el-form-item label="颜色"><el-input v-model="layerForm.color" /></el-form-item>
        <el-form-item label="风化程度"><el-input v-model="layerForm.weathering" /></el-form-item>
        <el-form-item label="结构构造"><el-input v-model="layerForm.structure" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="layerForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="layerForm.id" size="small" type="danger" @click="removeLayer">删除</el-button>
        <el-button size="small" @click="layerDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitLayer">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roundDialog" :title="roundForm.id ? '编辑回次' : '新增回次'" width="460px">
      <el-form :model="roundForm" label-width="108px" size="small">
        <el-form-item label="回次号"><el-input-number v-model="roundForm.roundNo" :min="1" /></el-form-item>
        <el-form-item label="起始深度 m"><el-input-number v-model="roundForm.fromDepth" :precision="2" /></el-form-item>
        <el-form-item label="终止深度 m"><el-input-number v-model="roundForm.toDepth" :precision="2" /></el-form-item>
        <el-form-item label="岩芯长度 m"><el-input-number v-model="roundForm.coreLength" :precision="2" /></el-form-item>
        <el-form-item label="采取率 %"><el-input-number v-model="roundForm.recoveryRate" :precision="2" /></el-form-item>
        <el-form-item label="RQD %"><el-input-number v-model="roundForm.rqd" :precision="2" /></el-form-item>
        <el-form-item label="记录员/班长"><el-input v-model="roundForm.driller" /></el-form-item>
        <el-form-item label="编录日期"><el-date-picker v-model="roundForm.sampledAt" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="低采取率原因">
          <el-select v-model="roundForm.lowRecoveryReason" clearable style="width: 100%">
            <el-option v-for="x in ['岩芯破碎', '漏失', '孔径失控']" :key="x" :label="x" :value="x" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="roundForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="roundForm.id" size="small" type="danger" @click="removeRound">删除</el-button>
        <el-button size="small" @click="roundDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitRound">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setDetail } from '../detail'
import { navigation, returnToHandover } from '../navigation'

const PALETTE = ['#6d4c41', '#8d6e63', '#a1887f', '#5d4037', '#795548', '#4e342e', '#bcaaa4', '#3e2723', '#c8a165', '#7b8d6e']

const holes = ref([])
const holeId = ref(null)
const layers = ref([])
const rounds = ref([])
const lithoStats = ref([])
const lowList = ref([])
const selectedId = ref(null)
const selectedRoundId = ref(null)
const handoverInfo = ref(null)
const layerDialog = ref(false)
const roundDialog = ref(false)
const layerForm = ref({})
const roundForm = ref({})

const currentHole = computed(() => holes.value.find((h) => h.id === holeId.value) || null)
const selectedRound = computed(() => rounds.value.find((r) => r.id === selectedRoundId.value) || null)
const selectedLayer = computed(() => layers.value.find((l) => l.id === selectedId.value) || null)
const fromHandover = computed(() => navigation.returnTab === 'handover' && !!navigation.handoverBoreholeId)
const lockStatus = computed(() => handoverInfo.value?.status || null)
const readonly = computed(() => lockStatus.value === '待检查' || lockStatus.value === '已接收')
const lockText = computed(() => {
  if (lockStatus.value === '待检查') return '待检查快照已锁定，退回后可整改'
  if (lockStatus.value === '已接收') return '已接收资料已锁定，需返开后修改'
  return ''
})

const maxDepth = computed(() => Math.max(...layers.value.map((l) => Number(l.toDepth)), 100))
const scaleMax = computed(() => Math.ceil(maxDepth.value / 50) * 50)

const ticks = computed(() => {
  const step = scaleMax.value > 600 ? 100 : 50
  const arr = []
  for (let v = 0; v <= scaleMax.value; v += step) arr.push(v)
  return arr
})

const colorMap = computed(() => {
  const names = [...new Set(layers.value.map((l) => l.lithology))].sort()
  const map = {}
  names.forEach((n, i) => { map[n] = PALETTE[i % PALETTE.length] })
  return map
})

const segments = computed(() =>
  layers.value.map((l) => {
    const round = rounds.value.find(
      (r) => Number(r.fromDepth) <= Number(l.fromDepth) + 0.01 && Number(r.toDepth) >= Number(l.toDepth) - 0.01
    )
    return {
      ...l,
      from: Number(l.fromDepth),
      to: Number(l.toDepth),
      rqd: round ? round.rqd : null,
      recovery: round ? round.recoveryRate : null
    }
  })
)

function num(v) {
  return v == null ? '—' : Number(v)
}

function depthY(d) {
  return 16 + (d / (scaleMax.value || 1)) * 624
}

function lithoColor(name) {
  return colorMap.value[name] || '#8d6e63'
}

function selectLayer(l) {
  selectedId.value = l.id
  setDetail({
    title: '第 ' + l.layerSeq + ' 层 · ' + l.lithology,
    subtitle: num(l.fromDepth) + ' ~ ' + num(l.toDepth) + ' m · 厚度 ' + num(Number(l.toDepth) - Number(l.fromDepth)) + ' m',
    tag: '岩性分层',
    fields: [
      { label: '层序', value: l.layerSeq },
      { label: '岩性', value: l.lithology },
      { label: '起始深度 (m)', value: num(l.fromDepth) },
      { label: '终止深度 (m)', value: num(l.toDepth) },
      { label: '颜色', value: l.color || '—' },
      { label: '风化程度', value: l.weathering || '—' },
      { label: '结构构造', value: l.structure || '—' },
      { label: '描述', value: l.description || '—' }
    ],
    json: l.attrs || {},
    jsonTitle: 'JSON 属性包（含水性 / 硬度 / 裂隙 / 矿化 …）',
    jsonEditable: true,
    onSave: async (attrs) => {
      const updated = await api.updateAttrs(l.id, attrs)
      const idx = layers.value.findIndex((x) => x.id === l.id)
      if (idx >= 0) layers.value[idx] = updated
      selectedId.value = updated.id
      setDetail({ json: updated.attrs || {} })
    }
  })
}

function selectRound(r) {
  selectedRoundId.value = r.id
  setDetail({
    title: '第 ' + r.roundNo + ' 回次',
    subtitle: num(r.fromDepth) + ' ~ ' + num(r.toDepth) + ' m',
    tag: '钻进回次',
    fields: [
      { label: '回次号', value: r.roundNo },
      { label: '起止深度 (m)', value: num(r.fromDepth) + ' ~ ' + num(r.toDepth) },
      { label: '进尺 (m)', value: num(Number(r.toDepth) - Number(r.fromDepth)) },
      { label: '岩芯长度 (m)', value: num(r.coreLength) },
      { label: '采取率 (%)', value: num(r.recoveryRate) },
      { label: 'RQD (%)', value: num(r.rqd) },
      { label: '钻工', value: r.driller || '—' },
      { label: '取样日期', value: r.sampledAt || '—' },
      { label: '低采取率原因', value: r.lowRecoveryReason || '—' },
      { label: '备注', value: r.remark || '—' }
    ]
  })
}

function openCreateLayer() {
  const last = layers.value.filter((l) => !selectedRoundId.value || l.roundId === selectedRoundId.value).slice(-1)[0]
  const round = selectedRound.value || rounds.value[0]
  layerForm.value = {
    roundId: round?.id || null,
    layerSeq: (layers.value.filter((l) => l.roundId === round?.id).length || 0) + 1,
    lithology: '',
    fromDepth: last ? Number(last.toDepth) : Number(round?.fromDepth || 0),
    toDepth: last ? Number(last.toDepth) + 10 : Number(round?.toDepth || 10),
    color: '', weathering: '', structure: '', description: ''
  }
  layerDialog.value = true
}

function openEditLayer() {
  if (!selectedLayer.value) return
  layerForm.value = { ...selectedLayer.value }
  layerDialog.value = true
}

async function submitLayer() {
  const body = { ...layerForm.value, boreholeId: holeId.value }
  if (body.id) await api.updateLayer(body.id, body)
  else await api.createLayer(body)
  ElMessage.success('岩性层已保存')
  layerDialog.value = false
  await loadAll()
  lithoStats.value = await api.lithoStats()
}

async function removeLayer() {
  await api.deleteLayer(layerForm.value.id)
  ElMessage.success('岩性层已删除')
  layerDialog.value = false
  await loadAll()
}

function openCreateRound() {
  const last = rounds.value[rounds.value.length - 1]
  roundForm.value = {
    roundNo: (rounds.value.length || 0) + 1,
    fromDepth: last ? Number(last.toDepth) : 0,
    toDepth: last ? Number(last.toDepth) + 10 : 10,
    coreLength: 9,
    recoveryRate: 90,
    rqd: 80,
    driller: '',
    sampledAt: '',
    lowRecoveryReason: null,
    remark: ''
  }
  roundDialog.value = true
}

function openEditRound() {
  if (!selectedRound.value) return
  roundForm.value = { ...selectedRound.value }
  roundDialog.value = true
}

async function submitRound() {
  const body = { ...roundForm.value, boreholeId: holeId.value }
  if (body.id) await api.updateRound(body.id, body)
  else await api.createRound(body)
  ElMessage.success('回次已保存')
  roundDialog.value = false
  await loadAll()
}

async function removeRound() {
  await api.deleteRound(roundForm.value.id)
  ElMessage.success('回次已删除')
  roundDialog.value = false
  selectedRoundId.value = null
  await loadAll()
}

async function loadAll() {
  if (!holeId.value) return
  layers.value = await api.layers({ boreholeId: holeId.value })
  rounds.value = await api.rounds(holeId.value)
  try {
    const hd = await api.handoverDetail(holeId.value)
    handoverInfo.value = hd.active
  } catch (e) {
    handoverInfo.value = null
  }
  const q = navigation.query || {}
  if (q.roundId) {
    selectedRoundId.value = q.roundId
    const targetRound = rounds.value.find((r) => r.id === q.roundId)
    if (targetRound) selectRound(targetRound)
  }
  if (q.layerId) {
    selectedId.value = q.layerId
    const targetLayer = layers.value.find((l) => l.id === q.layerId)
    if (targetLayer) selectLayer(targetLayer)
  }
  navigation.query = {}
}

function backToHandover() {
  returnToHandover()
}

watch(holeId, loadAll)

onMounted(async () => {
  holes.value = await api.boreholes()
  if (navigation.handoverBoreholeId) holeId.value = navigation.handoverBoreholeId
  else if (navigation.boreholeId) holeId.value = navigation.boreholeId
  else if (holes.value.length) holeId.value = holes.value[0].id
  lithoStats.value = await api.lithoStats()
  lowList.value = await api.lowRecovery()
  await loadAll()
})
</script>
