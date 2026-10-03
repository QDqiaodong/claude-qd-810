<template>
  <div class="dc-page">
    <div v-if="fromHandover" class="return-bar">
      <span>正在整改钻孔：<b>{{ holeNo(selected?.boreholeId || boreholeId) }}</b> 班报，补签后返回交接台重新提交。</span>
      <el-button size="small" type="primary" @click="backToHandover">返回交接台</el-button>
    </div>
    <div class="dc-toolbar noprint">
      <span class="dc-label">钻孔</span>
      <el-select v-model="boreholeId" size="small" style="width: 170px" clearable @change="loadReports">
        <el-option v-for="h in holes" :key="h.id" :label="h.holeNo" :value="h.id" />
      </el-select>
      <span class="dc-label">钻机</span>
      <el-select v-model="filterRigId" size="small" style="width: 150px" clearable @change="loadReports">
        <el-option v-for="r in rigs" :key="r.id" :label="r.code" :value="r.id" />
      </el-select>
      <el-button size="small" type="primary" :disabled="readonly" @click="openCreate">填写班报</el-button>
      <el-button v-if="selected" size="small" :disabled="readonly" @click="openEdit">编辑选中班报</el-button>
      <el-button v-if="selected" size="small" type="danger" :disabled="readonly" @click="removeSelected">删除</el-button>
      <el-tag v-if="lockText" type="danger">{{ lockText }}</el-tag>
      <el-button size="small" @click="printSheet">打印 / 预览</el-button>
      <span class="dc-label">共 {{ reports.length }} 张</span>
    </div>

    <div class="dc-split noprint">
      <div class="dc-panel" style="width: 300px; flex: none">
        <div class="dc-panel-title">班报列表（点选看纸质报表）</div>
        <div
          v-for="r in reports"
          :key="r.id"
          class="dc-round-row"
          :class="{ on: r.id === selectedId }"
          @click="selectReport(r)"
        >
          <span style="width: 96px; font-weight: 700">{{ r.reportDate }}</span>
          <span style="width: 52px">{{ r.shiftType }}班</span>
          <span style="width: 96px">{{ holeNo(r.boreholeId) }}</span>
          <span style="width: 92px">进尺 {{ num(r.footage) }} m</span>
          <span style="color: #8a7761">{{ r.leaderSign || '未签字' }}</span>
        </div>
        <div v-if="!reports.length" style="font-size: 12px; color: #8a7761">暂无班报</div>
      </div>

      <div class="dc-panel" style="flex: 1; min-width: 340px">
        <div class="dc-panel-title">材料消耗（JSON 明细）</div>
        <div v-for="m in materials" :key="m.name + m.spec" class="dc-round-row">
          <span style="width: 92px">{{ m.category }}</span>
          <span style="width: 130px; font-weight: 700">{{ m.name }}</span>
          <span style="width: 110px">{{ m.spec || '—' }}</span>
          <span>{{ num(m.qty) }} {{ m.unit }}</span>
        </div>
        <div v-if="!materials.length" style="font-size: 12px; color: #8a7761">选中班报后显示材料明细</div>
      </div>
    </div>

    <!-- 纸质班报表 -->
    <div class="dc-sheet" v-if="selected">
      <div class="dc-sheet-title">{{ mineNameOf(selected) }} 钻探班报表</div>
      <div class="dc-sheet-sub">编号 SR-{{ String(selected.id).padStart(4, '0') }} ｜ {{ selected.reportDate }} {{ selected.shiftType }}班</div>

      <table>
        <tr>
          <th>钻机编号</th><td>{{ rigCode(selected.rigId) }}</td>
          <th>施工孔号</th><td>{{ holeNo(selected.boreholeId) }}</td>
        </tr>
        <tr>
          <th>班组长</th><td>{{ crewLeader(selected.crewId) }}</td>
          <th>本班进尺</th><td>{{ num(selected.footage) }} m</td>
        </tr>
        <tr>
          <th>纯钻时间</th><td>{{ num(selected.pureHours) }} h</td>
          <th>辅助时间</th><td>{{ num(selected.auxHours) }} h</td>
        </tr>
        <tr>
          <th>时效分析</th>
          <td colspan="3">
            纯钻占比 {{ pureRatio(selected) }}% ｜ 台班效率 {{ perShift(selected) }} m/班
          </td>
        </tr>
        <tr>
          <th>事故与停待</th><td colspan="3">{{ selected.accidentNote || '本班无事故、无停待' }}</td>
        </tr>
        <tr>
          <th>交接班备注</th><td colspan="3">{{ selected.handoverNote || '—' }}</td>
        </tr>
      </table>

      <div style="margin-top: 12px; font-weight: 700; font-size: 13px">材料消耗明细</div>
      <table style="margin-top: 6px">
        <tr>
          <th style="width: auto">类别</th>
          <th style="width: auto">名称</th>
          <th style="width: auto">规格</th>
          <th style="width: auto">数量</th>
        </tr>
        <tr v-for="m in materials" :key="m.name + m.spec">
          <td style="width: auto">{{ m.category }}</td>
          <td style="width: auto">{{ m.name }}</td>
          <td style="width: auto">{{ m.spec || '—' }}</td>
          <td style="width: auto">{{ num(m.qty) }} {{ m.unit }}</td>
        </tr>
        <tr v-if="!materials.length">
          <td colspan="4" style="text-align: center; color: #8a7761">本班无材料消耗</td>
        </tr>
      </table>

      <div class="dc-sheet-sign">
        <span>班组长签字：{{ selected.leaderSign || '____________' }}</span>
        <span>机长核对：____________</span>
        <span>日期：{{ selected.reportDate }}</span>
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑班报' : '填写班报'" width="560px">
      <el-form :model="form" label-width="104px" size="small">
        <el-form-item label="施工孔号">
          <el-select v-model="form.boreholeId" style="width: 100%">
            <el-option v-for="h in holes" :key="h.id" :label="h.holeNo" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="钻机">
          <el-select v-model="form.rigId" style="width: 100%">
            <el-option v-for="r in rigs" :key="r.id" :label="r.code + ' · ' + r.model" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="班组">
          <el-select v-model="form.crewId" style="width: 100%" clearable>
            <el-option v-for="c in crews" :key="c.id" :label="c.leader + '（' + c.shiftType + '班）'" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期"><el-date-picker v-model="form.reportDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="班别">
          <el-select v-model="form.shiftType">
            <el-option v-for="s in ['早', '中', '夜']" :key="s" :label="s + '班'" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="本班进尺 m"><el-input-number v-model="form.footage" :precision="2" /></el-form-item>
        <el-form-item label="纯钻时间 h"><el-input-number v-model="form.pureHours" :precision="2" /></el-form-item>
        <el-form-item label="辅助时间 h"><el-input-number v-model="form.auxHours" :precision="2" /></el-form-item>
        <el-form-item label="事故与停待"><el-input v-model="form.accidentNote" /></el-form-item>
        <el-form-item label="交接班备注"><el-input v-model="form.handoverNote" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="班组长签字"><el-input v-model="form.leaderSign" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">保存班报</el-button>
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

const rigs = ref([])
const holes = ref([])
const crews = ref([])
const mines = ref([])
const reports = ref([])
const selectedId = ref(null)
const filterRigId = ref(null)
const boreholeId = ref(null)
const handoverInfo = ref(null)
const dialog = ref(false)
const form = ref({})

const selected = computed(() => reports.value.find((r) => r.id === selectedId.value) || null)
const materials = computed(() => (selected.value && selected.value.materials) || [])
const fromHandover = computed(() => navigation.returnTab === 'handover' && !!navigation.handoverBoreholeId)
const readonly = computed(() => handoverInfo.value?.status === '待检查' || handoverInfo.value?.status === '已接收')
const lockText = computed(() => {
  if (handoverInfo.value?.status === '待检查') return '待检查快照已锁定'
  if (handoverInfo.value?.status === '已接收') return '已接收资料已锁定，需返开'
  return ''
})

function num(v) {
  return v == null ? '—' : Number(v)
}

function rigCode(id) {
  const r = rigs.value.find((x) => x.id === id)
  return r ? r.code : '—'
}

function holeNo(id) {
  const h = holes.value.find((x) => x.id === id)
  return h ? h.holeNo : '—'
}

function crewLeader(id) {
  const c = crews.value.find((x) => x.id === id)
  return c ? c.leader : '—'
}

function mineNameOf(r) {
  const h = holes.value.find((x) => x.id === r.boreholeId)
  if (!h) return '钻探'
  const m = mines.value.find((x) => x.id === h.mineId)
  return m ? m.name : '钻探'
}

function pureRatio(r) {
  const pure = Number(r.pureHours || 0)
  const aux = Number(r.auxHours || 0)
  if (pure + aux === 0) return '0.00'
  return ((pure / (pure + aux)) * 100).toFixed(2)
}

function perShift(r) {
  const pure = Number(r.pureHours || 0)
  if (!pure) return '0.00'
  return (Number(r.footage || 0) / (pure / 8)).toFixed(2)
}

function selectReport(r) {
  selectedId.value = r.id
  setDetail({
    title: r.reportDate + ' ' + r.shiftType + '班',
    subtitle: rigCode(r.rigId) + ' · ' + holeNo(r.boreholeId),
    tag: '班报表',
    fields: [
      { label: '班报日期', value: r.reportDate },
      { label: '班别', value: r.shiftType + '班' },
      { label: '钻机', value: rigCode(r.rigId) },
      { label: '施工孔号', value: holeNo(r.boreholeId) },
      { label: '班组长', value: crewLeader(r.crewId) },
      { label: '本班进尺 (m)', value: num(r.footage) },
      { label: '纯钻时间 (h)', value: num(r.pureHours) },
      { label: '辅助时间 (h)', value: num(r.auxHours) },
      { label: '纯钻占比 (%)', value: pureRatio(r) },
      { label: '事故与停待', value: r.accidentNote || '—' },
      { label: '交接班备注', value: r.handoverNote || '—' },
      { label: '班组长签字', value: r.leaderSign || '—' }
    ],
    json: r.materials || [],
    jsonTitle: 'JSON 材料消耗明细'
  })
}

function printSheet() {
  window.print()
}

function openCreate() {
  form.value = {
    boreholeId: boreholeId.value || holes.value[0]?.id,
    rigId: rigs.value[0]?.id,
    crewId: null,
    reportDate: new Date().toISOString().slice(0, 10),
    shiftType: '早',
    footage: 0,
    pureHours: 0,
    auxHours: 0,
    accidentNote: '',
    handoverNote: '',
    leaderSign: ''
  }
  dialog.value = true
}

function openEdit() {
  if (!selected.value) return
  form.value = { ...selected.value }
  dialog.value = true
}

async function removeSelected() {
  await api.deleteReport(selected.value.id)
  ElMessage.success('班报已删除')
  selectedId.value = null
  await loadReports()
}

async function submitCreate() {
  if (!form.value.boreholeId || !form.value.rigId) {
    ElMessage.warning('请选择施工孔号与钻机')
    return
  }
  const payload = { ...form.value, materials: form.value.materials || [] }
  if (payload.id) await api.updateReport(payload.id, payload)
  else await api.createReport(payload)
  ElMessage.success(payload.id ? '班报已更新' : '班报已保存')
  dialog.value = false
  await loadReports()
}

async function loadReports() {
  const params = boreholeId.value
    ? { boreholeId: boreholeId.value }
    : filterRigId.value
      ? { rigId: filterRigId.value }
      : {}
  reports.value = await api.reports(params)
  const targetId = navigation.query?.reportId
  if (targetId && reports.value.some((r) => r.id === targetId)) {
    selectedId.value = targetId
    selectReport(reports.value.find((r) => r.id === targetId))
  }
  else if (reports.value.length && !selectedId.value) selectReport(reports.value[0])
  if (boreholeId.value) {
    try {
      handoverInfo.value = (await api.handoverDetail(boreholeId.value)).active
    } catch (e) {
      handoverInfo.value = null
    }
  } else {
    handoverInfo.value = null
  }
  navigation.query = {}
}

function backToHandover() {
  returnToHandover()
}

watch(boreholeId, () => {
  selectedId.value = null
  loadReports()
})

onMounted(async () => {
  rigs.value = await api.rigs()
  holes.value = await api.boreholes()
  crews.value = await api.crews()
  mines.value = await api.mines()
  if (navigation.handoverBoreholeId) boreholeId.value = navigation.handoverBoreholeId
  else if (navigation.boreholeId) boreholeId.value = navigation.boreholeId
  else if ((navigation.query || {}).boreholeId) boreholeId.value = navigation.query.boreholeId
  await loadReports()
})
</script>
