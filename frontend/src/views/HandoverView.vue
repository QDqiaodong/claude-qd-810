<template>
  <div class="dc-page">
    <div class="dc-toolbar">
      <span class="dc-label">钻孔</span>
      <el-select v-model="boreholeId" size="small" style="width: 230px" filterable @change="loadDetail">
        <el-option v-for="h in handovers" :key="h.boreholeId"
                   :label="h.holeNo + ' · ' + h.status + ' · V' + (h.versionNo || '—')"
                   :value="h.boreholeId" />
      </el-select>
      <el-radio-group v-model="role" size="small">
        <el-radio-button label="crew">现场班组</el-radio-button>
        <el-radio-button label="geo">地质负责人</el-radio-button>
      </el-radio-group>
      <el-tag :type="statusType(summary.status)">{{ summary.status }}</el-tag>
      <el-tag v-if="summary.status === '已接收'" type="success">资料已锁定</el-tag>
      <div style="margin-left: auto; display: flex; gap: 8px">
        <el-button v-if="role === 'crew' && !summary.handoverId" size="small" type="primary" @click="openAction('start')">
          发起交接
        </el-button>
        <el-button v-if="role === 'crew' && active?.status === '待整改'" size="small" type="primary" @click="openAction('resubmit')">
          整改后重新提交
        </el-button>
        <template v-if="role === 'geo'">
          <el-button v-if="active?.status === '待检查'" size="small" type="warning" @click="openAction('reject')">退回整改</el-button>
          <el-button v-if="active?.status === '待检查'" size="small" type="success" :disabled="openCount > 0" @click="openAction('accept')">
            确认接收
          </el-button>
          <el-button v-if="active?.status === '已接收'" size="small" type="danger" @click="openAction('reopen')">办理返开</el-button>
        </template>
      </div>
    </div>

    <el-alert
      v-if="active?.status === '待检查'"
      title="当前为发起时刻的不可变快照；待检查期间回次、岩性层、参数和班报录入入口均已锁定。"
      type="info" :closable="false" style="margin-bottom: 12px"
    />
    <el-alert
      v-if="active?.status === '待整改'"
      title="可按问题项回到源资料整改；问题未清零时只能保存进度或重新提交，不能确认接收。"
      type="warning" :closable="false" style="margin-bottom: 12px"
    />
    <el-alert
      v-if="active?.status === '已接收'"
      title="已接收版本归档并锁定全部相关资料；返开须填写原因，原接收版本会标记为已失效。"
      type="success" :closable="false" style="margin-bottom: 12px"
    />

    <div class="dc-split">
      <div class="dc-panel" style="width: 300px; flex: none">
        <div class="dc-panel-title">交接队列</div>
        <div v-for="h in handovers" :key="h.boreholeId" class="dc-round-row handover-row"
             :class="{ on: h.boreholeId === boreholeId }" @click="selectHole(h.boreholeId)">
          <div><b>{{ h.holeNo }}</b><el-tag size="small" :type="statusType(h.status)" style="margin-left: 8px">{{ h.status }}</el-tag></div>
          <div class="handover-meta">孔深 {{ num(h.actualDepth) }} m · V{{ h.versionNo || '—' }}</div>
          <div v-if="h.handoverId" :class="h.openIssueCount ? 'handover-bad' : 'handover-ok'">
            待处理 {{ h.openIssueCount }} / {{ h.issueCount }}
          </div>
        </div>
      </div>

      <div style="flex: 1; min-width: 520px">
        <div class="dc-panel">
          <div class="dc-panel-title">当前版本与孔深快照</div>
          <div v-if="!active" style="font-size: 12px; color: #8a7761">
            尚未发起终孔交接。现场班组可在实际孔深与资料确认后发起，系统会立即冻结当时的资料快照。
          </div>
          <div v-else class="handover-grid">
            <span>版本：V{{ active.versionNo }}</span>
            <span>状态：<b>{{ active.status }}</b></span>
            <span>当前孔深：{{ num(currentBorehole?.actualDepth) }} m</span>
            <span>快照孔深：{{ num(active.snapshotDepth) }} m</span>
            <span>提交人：{{ active.submittedBy || '—' }}</span>
            <span>提交时间：{{ formatTime(active.submittedAt) }}</span>
            <span>检查人：{{ active.checkedBy || '—' }}</span>
            <span>检查时间：{{ formatTime(active.checkedAt) }}</span>
          </div>
          <div v-if="active?.rejectReason && active.status === '待整改'" class="handover-box warning">
            <b>退回原因：</b>{{ active.rejectReason }}
            <div v-if="active.lastInspectionJson" style="margin-top: 6px">
              上一轮检查：{{ active.lastInspectionJson.checkedBy }} · {{ formatTime(active.lastInspectionJson.checkedAt) }}
              · 待处理 {{ active.lastInspectionJson.openIssueCount }} 项
              <div v-if="active.lastInspectionJson.note">检查备注：{{ active.lastInspectionJson.note }}</div>
            </div>
          </div>
          <div v-if="active?.reopenReason" class="dc-warn">
            <b>返开原因：</b>{{ active.reopenReason }}（{{ active.reopenedBy }} · {{ formatTime(active.reopenedAt) }}）
          </div>
        </div>

        <div class="dc-panel">
          <div class="dc-panel-title">
            完整性待处理项
            <el-tag size="small" :type="openCount ? 'danger' : 'success'" style="margin-left: 8px">
              待处理 {{ openCount }} / {{ currentIssues.length }}
            </el-tag>
          </div>
          <el-table :data="currentIssues" size="small" border>
            <el-table-column prop="type" label="类型" width="70">
              <template #default="{ row }">{{ typeName(row.type) }}</template>
            </el-table-column>
            <el-table-column label="问题" min-width="230">
              <template #default="{ row }">
                <div>{{ row.message }}</div>
                <div style="color: #8a7761; font-size: 11px">{{ row.requiredAction }}</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.resolved ? 'success' : 'danger'">{{ row.resolved ? '已处理' : '待处理' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240">
              <template #default="{ row }">
                <el-button size="small" @click="jump(row)">回去整改</el-button>
                <el-button v-if="row.type === 'param' && active?.status === '待整改'" size="small" type="primary"
                           @click="resolveIssue(row)">
                  {{ row.resolved ? '更新说明' : '标记已核查' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="liveSnapshot" class="handover-box info" style="margin-top: 10px">
            返开整改中暂无冻结快照，下方显示实时数据；点击“整改后重新提交”时才生成新版本快照。
          </div>
        </div>

        <div class="dc-panel">
          <el-collapse v-model="expanded">
            <el-collapse-item title="孔深 / 回次 / 岩性层快照" name="core">
              <div class="snapshot-section">
                <b>回次（{{ currentData.rounds?.length || 0 }}）</b>
                <div v-for="r in currentData.rounds || []" :key="r.id" class="dc-round-row">
                  <span style="width: 80px">第{{ r.roundNo }}回次</span>
                  <span style="width: 150px">{{ num(r.fromDepth) }}~{{ num(r.toDepth) }} m</span>
                  <span style="width: 110px">采取率 {{ num(r.recoveryRate) }}%</span>
                  <span>{{ r.lowRecoveryReason || '—' }}</span>
                </div>
                <b style="margin-top: 10px">岩性层（{{ currentData.layers?.length || 0 }}）</b>
                <div v-for="l in currentData.layers || []" :key="l.id" class="dc-round-row">
                  <span style="width: 130px">{{ l.lithology }}</span>
                  <span>{{ num(l.fromDepth) }}~{{ num(l.toDepth) }} m</span>
                </div>
              </div>
            </el-collapse-item>
            <el-collapse-item title="钻进参数与异常点" name="param">
              <div v-for="a in currentData.paramAnalyses || []" :key="a.seriesId" class="dc-warn"
                   :style="{ borderColor: a.abnormalCount ? '#c05621' : '#9fbf8f' }">
                <span style="width: 120px">{{ a.recordDate }} {{ a.shiftType }}班</span>
                <span style="width: 100px">采样 {{ a.pointCount }} 点</span>
                <span :style="{ color: a.abnormalCount ? '#c05621' : '#507a3f' }">异常 {{ a.abnormalCount }} 点</span>
                <span style="margin-left: auto">{{ a.operator || '—' }}</span>
              </div>
            </el-collapse-item>
            <el-collapse-item title="班报完整性与签字" name="report">
              <div v-for="r in currentData.shiftReports || []" :key="r.id" class="dc-round-row">
                <span style="width: 120px">{{ r.reportDate }} {{ r.shiftType }}班</span>
                <span style="width: 110px">进尺 {{ num(r.footage) }} m</span>
                <span :style="{ color: r.leaderSign ? '#507a3f' : '#c05621' }">
                  {{ r.leaderSign || '缺少签字' }}
                </span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="dc-panel">
          <div class="dc-panel-title">历史版本（只读还原）</div>
          <el-timeline>
            <el-timeline-item v-for="v in history" :key="v.id"
                              :type="v.superseded ? 'danger' : statusType(v.status) === 'success' ? 'success' : 'primary'"
                              :timestamp="formatTime(v.submittedAt)">
              <div class="history-title" @click="viewingVersionId = viewingVersionId === v.id ? null : v.id">
                V{{ v.versionNo }} · {{ v.status }} · 快照孔深 {{ num(v.snapshotDepth) }} m
                <span v-if="v.superseded" class="invalid-badge">已失效</span>
              </div>
              <div v-if="viewingVersionId === v.id" class="snapshot-section">
                <div v-if="v.rejectReason && v.status !== '待检查'"><b>退回原因：</b>{{ v.rejectReason }}</div>
                <div v-else-if="v.lastInspectionJson"><b>上一轮检查：</b>{{ v.lastInspectionJson.reason }}</div>
                <div v-if="v.reopenReason"><b>返开原因：</b>{{ v.reopenReason }}</div>
                <div>回次 {{ v.snapshotJson?.rounds?.length || 0 }} 条，
                  岩性层 {{ v.snapshotJson?.layers?.length || 0 }} 层，
                  参数 {{ v.snapshotJson?.paramSeries?.length || 0 }} 份，
                  班报 {{ v.snapshotJson?.shiftReports?.length || 0 }} 张，
                  问题 {{ v.issuesJson?.length || 0 }} 项。
                </div>
                <el-table :data="v.snapshotJson?.rounds || []" size="small" border style="margin-top: 8px">
                  <el-table-column prop="roundNo" label="回次" width="70" />
                  <el-table-column label="深度范围">
                    <template #default="{ row }">{{ num(row.fromDepth) }} ~ {{ num(row.toDepth) }} m</template>
                  </el-table-column>
                  <el-table-column prop="recoveryRate" label="采取率%" width="90" />
                  <el-table-column prop="lowRecoveryReason" label="低采取率原因" />
                </el-table>
                <el-table :data="v.snapshotJson?.layers || []" size="small" border style="margin-top: 8px">
                  <el-table-column prop="lithology" label="岩性" width="130" />
                  <el-table-column label="深度范围">
                    <template #default="{ row }">{{ num(row.fromDepth) }} ~ {{ num(row.toDepth) }} m</template>
                  </el-table-column>
                  <el-table-column prop="weathering" label="风化" width="90" />
                  <el-table-column prop="description" label="描述" />
                </el-table>
                <el-table :data="v.snapshotJson?.paramAnalyses || []" size="small" border style="margin-top: 8px">
                  <el-table-column label="参数时序">
                    <template #default="{ row }">{{ row.recordDate }} {{ row.shiftType }}班</template>
                  </el-table-column>
                  <el-table-column prop="pointCount" label="采样点" width="80" />
                  <el-table-column label="异常点" width="80">
                    <template #default="{ row }">
                      <span :style="{ color: row.abnormalCount ? '#c05621' : '#507a3f' }">{{ row.abnormalCount }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="operator" label="记录人" width="90" />
                </el-table>
                <el-table :data="v.snapshotJson?.shiftReports || []" size="small" border style="margin-top: 8px">
                  <el-table-column label="班报">
                    <template #default="{ row }">{{ row.reportDate }} {{ row.shiftType }}班</template>
                  </el-table-column>
                  <el-table-column prop="footage" label="进尺m" width="90" />
                  <el-table-column label="签字">
                    <template #default="{ row }">{{ row.leaderSign || '未签字' }}</template>
                  </el-table-column>
                </el-table>
                <el-table :data="v.issuesJson || []" size="small" border style="margin-top: 8px">
                  <el-table-column prop="message" label="当时问题" />
                  <el-table-column label="状态" width="80">
                    <template #default="{ row }">{{ row.resolved ? '已处理' : '待处理' }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form label-width="92px" size="small">
        <el-form-item v-if="dialogType === 'reject' || dialogType === 'reopen'" label="原因" required>
          <el-input v-model="reason" type="textarea" :rows="3"
                    :placeholder="dialogType === 'reject' ? '请说明退回整改原因' : '请说明已接收资料为什么必须返开'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="note" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitAction">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { navigation, openIssueTarget } from '../navigation'

const handovers = ref([])
const detailData = ref({})
const boreholeId = ref(null)
const role = ref('crew')
const expanded = ref(['core', 'param', 'report'])
const viewingVersionId = ref(null)
const dialogVisible = ref(false)
const dialogType = ref('')
const reason = ref('')
const note = ref('')

const currentBorehole = computed(() => detailData.value.borehole || null)
const active = computed(() => detailData.value.active || null)
const history = computed(() => detailData.value.history || [])
const liveSnapshot = computed(() => detailData.value.reopenLiveSnapshot || null)
const summary = computed(() => handovers.value.find((h) => h.boreholeId === boreholeId.value) || { status: '未发起' })
const currentData = computed(() => active.value?.snapshotJson || liveSnapshot.value || {})
const currentIssues = computed(() => {
  if (liveSnapshot.value) return liveSnapshot.value.issues || []
  return active.value?.issuesJson || currentData.value.issues || []
})
const openCount = computed(() => currentIssues.value.filter((i) => !i.resolved).length)
const dialogTitle = computed(() => ({
  start: '发起终孔交接',
  resubmit: '整改后重新提交',
  reject: '退回整改',
  accept: '确认接收',
  reopen: '办理返开'
}[dialogType.value]))

function num(v) { return v == null ? '—' : Number(v) }
function formatTime(v) { return v ? String(v).replace('T', ' ').slice(0, 16) : '—' }
function typeName(type) { return { round: '回次', layer: '岩性层', param: '参数', report: '班报' }[type] || '资料' }
function statusType(status) {
  if (status === '已接收') return 'success'
  if (status === '待整改') return 'warning'
  if (status === '待检查') return 'primary'
  if (status === '已失效') return 'danger'
  return 'info'
}

async function loadList(preferredId = boreholeId.value) {
  handovers.value = await api.handovers()
  if (!preferredId && handovers.value.length) boreholeId.value = handovers.value[0].boreholeId
}

async function loadDetail() {
  if (!boreholeId.value) {
    detailData.value = {}
    return
  }
  navigation.handoverBoreholeId = boreholeId.value
  detailData.value = await api.handoverDetail(boreholeId.value)
  navigation.handoverVersionId = active.value?.id || null
}

async function selectHole(id) {
  boreholeId.value = id
  viewingVersionId.value = null
  await loadDetail()
}

function openAction(type) {
  dialogType.value = type
  reason.value = ''
  note.value = ''
  dialogVisible.value = true
}

async function submitAction() {
  const actor = role.value === 'geo' ? '地质负责人' : '现场班组'
  const body = { actor, reason: reason.value, note: note.value, expectedVersion: active.value?.lockVersion ?? null }
  const id = active.value?.id
  try {
    if (dialogType.value === 'start') await api.startHandover(boreholeId.value, body)
    if (dialogType.value === 'resubmit') await api.resubmitHandover(id, body)
    if (dialogType.value === 'reject') await api.rejectHandover(id, body)
    if (dialogType.value === 'accept') await api.acceptHandover(id, body)
    if (dialogType.value === 'reopen') await api.reopenHandover(id, body)
    dialogVisible.value = false
    await loadList(boreholeId.value)
    await loadDetail()
  } catch (e) {
    if (e?.response?.status === 409) {
      await loadList(boreholeId.value)
      await loadDetail()
    }
  }
}

async function resolveIssue(issue) {
  const resolution = window.prompt('请填写参数异常处理说明', issue.resolution || '已核对孔内情况与设备调整记录，异常点已说明。')
  if (!resolution) return
  await api.saveIssueProgress(active.value.id, {
    issueCode: issue.code,
    resolved: true,
    resolution,
    actor: '现场班组'
  })
  await loadDetail()
}

function jump(issue) {
  openIssueTarget(issue.target, {
    handoverBoreholeId: boreholeId.value,
    handoverVersionId: active.value?.id
  })
}

onMounted(async () => {
  await loadList(navigation.handoverBoreholeId)
  if (navigation.handoverBoreholeId) boreholeId.value = navigation.handoverBoreholeId
  else if (!boreholeId.value && handovers.value.length) boreholeId.value = handovers.value[0].boreholeId
  await loadDetail()
})
</script>
