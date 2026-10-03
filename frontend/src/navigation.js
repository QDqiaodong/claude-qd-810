import { reactive } from 'vue'

/**
 * 跨页面整改导航。问题项 target 形如 /core?boreholeId=3&roundId=5，
 * 跳走后仍保留当前钻孔与交接台检查进度。
 */
export const navigation = reactive({
  current: 'holes',
  boreholeId: null,
  query: {},
  returnTab: 'handover',
  handoverBoreholeId: null,
  handoverVersionId: null
})

const TAB_BY_PATH = {
  '/holes': 'holes',
  '/core': 'core',
  '/param': 'param',
  '/report': 'report',
  '/rig': 'rig'
}

export function openIssueTarget(target, context = {}) {
  const [path, queryText] = String(target || '').split('?')
  const tab = TAB_BY_PATH[path]
  if (!tab) return
  const query = {}
  new URLSearchParams(queryText || '').forEach((rawValue, key) => {
    const value = rawValue.trim()
    query[key] = /^\d+$/.test(value) ? Number(value) : value
  })
  navigation.returnTab = 'handover'
  navigation.handoverBoreholeId = context.handoverBoreholeId ?? navigation.handoverBoreholeId
  navigation.handoverVersionId = context.handoverVersionId ?? navigation.handoverVersionId
  navigation.current = tab
  navigation.boreholeId = query.boreholeId || navigation.handoverBoreholeId
  navigation.query = query
}

export function returnToHandover() {
  navigation.current = 'handover'
  navigation.boreholeId = navigation.handoverBoreholeId
  navigation.returnTab = ''
}

export function selectBoreholeFromAny(tab, boreholeId) {
  navigation.current = tab
  navigation.boreholeId = boreholeId
  navigation.query = {}
}
