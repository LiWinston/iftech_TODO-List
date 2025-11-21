import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from './auth'
import './styles.css'

export type Todo = {
  id: string
  userId: string
  title: string
  description?: string
  statusCode: number
  createdAt: string
  priorityScore?: number
  priorityLabel?: string
  categoryId?: string
}

interface PageCursor { createdAt?: string; id?: string }

export default function App() {
  const { token, userId, ensureAuthModal, logout, LoginModal } = useAuth()
  const [todos, setTodos] = useState<Todo[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const cursor = useRef<PageCursor>({})
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [searchQ, setSearchQ] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchResults, setSearchResults] = useState<Todo[]>([])
  const [view, setView] = useState<'list' | 'search'>('list')
  const [prioScore, setPrioScore] = useState<string>('0')
  const [prioLabel, setPrioLabel] = useState<string>('')
  const [category, setCategory] = useState<string>('')
  const [showTrash, setShowTrash] = useState(false)
  const [trash, setTrash] = useState<Todo[]>([])
  const [loadingTrash, setLoadingTrash] = useState(false)
  const [showPriorityMgr, setShowPriorityMgr] = useState(false)
  // ===== 用户级配置（优先级层级、分类、标签）及筛选排序 =====
  const [priorityLevels, setPriorityLevels] = useState<{id:string; name:string}[]>([])
  const [categories, setCategories] = useState<{id:string; name:string}[]>([])
  const [tags, setTags] = useState<{id:string; name:string}[]>([])
  const [selectedPriorityLevel, setSelectedPriorityLevel] = useState<string>('')
  const [selectedTags, setSelectedTags] = useState<string[]>([])
  const [sortField, setSortField] = useState<'created'|'priority'>('created')
  const [sortOrder, setSortOrder] = useState<'desc'|'asc'>('desc')

  const size = useRef(20)
  const lastScrollTs = useRef<number>(Date.now())
  // 额外的请求中标记，避免 loadMore 在同一渲染周期或滚动抖动中被重复触发
  const loadingRef = useRef(false)
  // 轻量节流：最小两次加载间隔与待触发的定时器
  const loadCooldownMs = 250
  const lastLoadAtRef = useRef(0)
  const loadTimerRef = useRef<number | null>(null)

  const authHeaders = () => ({ 'Authorization': token ? `Bearer ${token}` : '', 'X-User-ID': userId || '' })

  const fetchJson = useCallback(async (url: string, init?: RequestInit, autoLoginOn403: boolean = false) => {
    // 规范化 headers 为普通对象
    const base: Record<string,string> = {}
    if (init?.headers instanceof Headers) {
      init.headers.forEach((v,k)=>{ base[k]=v })
    } else if (Array.isArray(init?.headers)) {
      for (const [k,v] of init.headers as any) base[k]=v
    } else if (init?.headers) {
      Object.assign(base, init.headers as any)
    }
    const headers: Record<string,string> = { 'Content-Type': 'application/json', ...base, ...authHeaders() }
    const res = await fetch(url, { ...(init || {}), headers })
    if (res.status === 403 && autoLoginOn403) {
      ensureAuthModal()
      throw new Error('需要登录')
    }
    if (!res.ok) throw new Error(await res.text())
    const ct = res.headers.get('content-type')
    if (ct && ct.includes('application/json')) return res.json()
    return res.text()
  }, [token, userId, ensureAuthModal])

  const adjustSize = useCallback((deltaY: number) => {
    const now = Date.now()
    const dt = now - lastScrollTs.current
    lastScrollTs.current = now
    const speed = Math.abs(deltaY) / Math.max(1, dt)
    const next = Math.max(20, Math.min(150, Math.round(size.current * (1 + speed * 0.25))))
    size.current = next
  }, [])

  const loadMore = useCallback(async () => {
    // 双重保护：state 与 ref
    if (loadingRef.current || loading || !hasMore || view !== 'list') return
    const now = Date.now()
    if (now - lastLoadAtRef.current < loadCooldownMs) return
    lastLoadAtRef.current = now
    loadingRef.current = true
    setLoading(true)
    try {
      const params = new URLSearchParams()
      params.set('size', String(size.current))
      if (cursor.current.createdAt) params.set('cursorCreatedAt', cursor.current.createdAt)
      if (cursor.current.id) params.set('cursorId', cursor.current.id)
      params.set('sort', sortField)
      params.set('order', sortOrder)
      if (selectedPriorityLevel) params.set('priorityLevelId', selectedPriorityLevel)
      if (selectedTags.length) params.set('tags', selectedTags.join(','))
      const data: Todo[] = await fetchJson(`/api/todos?${params.toString()}`)
      // 去重合并：避免在开发模式（React StrictMode）或偶发重复请求时列表重复展示
      setTodos(prev => {
        if (prev.length === 0) return data
        const exists = new Set(prev.map(t => t.id))
        const toAdd = data.filter(t => !exists.has(t.id))
        return [...prev, ...toAdd]
      })
      if (data.length > 0) {
        const last = data[data.length - 1]
        cursor.current = { createdAt: last.createdAt, id: last.id }
        setHasMore(true)
      } else {
        setHasMore(false)
      }
    } catch (e) { console.warn(e) }
    finally { setLoading(false); loadingRef.current = false }
  }, [loading, hasMore, view, fetchJson, sortField, sortOrder, selectedPriorityLevel, selectedTags])

  // 当视图或排序/筛选参数变化时刷新（不依赖 loadMore，避免其引用因 loading 变化触发循环）
  useEffect(() => {
    cursor.current = {}
    setTodos([])
    setHasMore(true)
    // 重置 loadingRef，确保新一轮能拉取
    loadingRef.current = false
    lastLoadAtRef.current = 0
    if (view === 'list') {
      // 仅在没有正在进行的请求时触发首次加载（避免重复）
      if (!loadingRef.current) loadMore()
    }
  }, [view, sortField, sortOrder, selectedPriorityLevel, selectedTags])

  // 使用一个 ref 调用最新的 loadMore，避免把 loadMore 本身放入依赖引起不必要重建
  const loadMoreRef = useRef(loadMore)
  useEffect(()=>{ loadMoreRef.current = loadMore }, [loadMore])
  const onScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    if (view !== 'list') return
    adjustSize((e.nativeEvent as any).deltaY ?? 1)
    const el = e.currentTarget
    const nearBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 120
    if (nearBottom && !loadingRef.current) {
      // 简单去抖：合并 120ms 内的多次触发为一次
      if (loadTimerRef.current == null) {
        loadTimerRef.current = window.setTimeout(() => {
          loadTimerRef.current = null
          loadMoreRef.current()
        }, 120)
      }
    }
  }, [adjustSize, view])

  // 卸载清理未触发的定时器
  useEffect(() => {
    return () => { if (loadTimerRef.current != null) { clearTimeout(loadTimerRef.current); loadTimerRef.current = null } }
  }, [])

  const onCreate = useCallback(async () => {
    if (!title.trim()) return
    const body = {
      title,
      description: desc || null,
      priorityScore: prioScore ? Number(prioScore) : 0,
      priorityLabel: prioLabel || null,
      categoryId: category || null,
      priorityLevelId: selectedPriorityLevel || null,
      tagIds: selectedTags
    }
    try {
      const created = await fetchJson('/api/todos', { method: 'POST', body: JSON.stringify(body) }, true) as Todo
      // 根据当前排序策略决定插入方式：仅在按创建时间倒序时直接前插，其它排序重载列表确保顺序
      if (view === 'list' && sortField === 'created' && sortOrder === 'desc') {
        setTodos(prev => [created, ...prev])
      } else {
        cursor.current = {}
        setTodos([])
        setHasMore(true)
        // 异步触发重新加载，保证最新创建任务按排序规则出现
        setTimeout(()=>{ loadMore() },0)
      }
      // 重置输入表单
      setTitle('')
      setDesc('')
      setPrioScore('0')
      setPrioLabel('')
      setCategory('')
      setSelectedPriorityLevel('')
      setSelectedTags([])
    } catch (e) { console.warn(e) }
  }, [title, desc, fetchJson, prioScore, prioLabel, category, selectedPriorityLevel, selectedTags, view, sortField, sortOrder, loadMore])

  const onDelete = useCallback(async (id: string) => {
    try {
      await fetchJson(`/api/todos/${id}/trash`, { method: 'POST' }, true)
      setTodos(prev => prev.filter(t => t.id !== id))
      if (view === 'search') setSearchResults(prev => prev.filter(t => t.id !== id))
      // 如果回收站已打开，刷新
      if (showTrash) loadTrash()
    } catch (e) { console.warn(e) }
  }, [fetchJson, view, showTrash])

  const onToggle = useCallback(async (id: string, completed: boolean) => {
    try {
      await fetchJson(`/api/todos/${id}/${completed ? 'uncomplete' : 'complete'}`, { method: 'POST' }, true)
      setTodos(prev => prev.map(t => t.id === id ? { ...t, statusCode: completed ? 0 : 1 } : t))
      if (view === 'search') setSearchResults(prev => prev.map(t => t.id === id ? { ...t, statusCode: completed ? 0 : 1 } : t))
    } catch (e) { console.warn(e) }
  }, [fetchJson, view])

  // 搜索函数（命名为 doSearch，避免与创建函数重名）
  const doSearch = useCallback(async () => {
    if (!searchQ.trim()) { setView('list'); setSearchResults([]); return }
    setSearching(true)
    setView('search')
    try {
      const data = await fetchJson(`/api/todos/search?q=${encodeURIComponent(searchQ.trim())}`) as Todo[]
      setSearchResults(data)
    } catch (e) { console.warn(e) }
    finally { setSearching(false) }
  }, [searchQ, fetchJson])

  const clearSearch = useCallback(() => {
    setSearchQ('')
    setView('list')
    setSearchResults([])
    cursor.current = {}
    setTodos([])
    setHasMore(true)
  }, [])

  // 加载用户配置（优先级层级、分类、标签）
  useEffect(() => {
    const loadConfig = async () => {
      try {
        const [pls, cats, tgs] = await Promise.all([
          fetchJson('/api/config/priority-levels'),
          fetchJson('/api/config/categories'),
          fetchJson('/api/config/tags')
        ])
        setPriorityLevels(pls as any)
        setCategories(cats as any)
        setTags(tgs as any)
      } catch (e) { console.warn(e) }
    }
    loadConfig()
  }, [token, fetchJson])

  // 抽取刷新优先级层级函数供管理弹窗使用
  const refreshPriorityLevels = useCallback(async () => {
    try {
      const pls = await fetchJson('/api/config/priority-levels') as any
      setPriorityLevels(pls)
    } catch (e) { console.warn(e) }
  }, [fetchJson])

  const list = view === 'list' ? todos : searchResults

  const loadTrash = useCallback(async () => {
    setLoadingTrash(true)
    try {
      const data = await fetchJson('/api/todos?status=TRASHED&size=200') as Todo[]
      setTrash(data)
    } catch (e) { console.warn(e) }
    finally { setLoadingTrash(false) }
  }, [fetchJson])

  const onRestore = useCallback(async (id: string) => {
    try {
      await fetchJson(`/api/todos/${id}/restore`, { method: 'POST' }, true)
      setTrash(prev => prev.filter(t => t.id !== id))
      // 恢复后重新插入主列表顶部（不保证原排序位置）
      const found = trash.find(t => t.id === id)
      if (found) setTodos(prev => [ { ...found, statusCode: 0 }, ...prev ])
    } catch (e) { console.warn(e) }
  }, [fetchJson, trash])

  const onPurge = useCallback(async (id: string) => {
    if (!window.confirm('确定要彻底删除该任务吗？此操作不可恢复。')) return
    try {
      await fetchJson(`/api/todos/${id}/purge`, { method: 'POST' }, true)
      setTrash(prev => prev.filter(t => t.id !== id))
    } catch (e) { console.warn(e) }
  }, [fetchJson])

  useEffect(() => { if (showTrash) loadTrash() }, [showTrash, loadTrash])

  return (
    <div className="app-shell">
      <LoginModal />
      <header className="app-header">
        <div className="brand">📝 TODO List</div>
        <button className="btn outline" onClick={()=>setShowTrash(true)}>垃圾桶 ({trash.length})</button>
        <div className="search-bar">
          <input value={searchQ} onChange={e=>setSearchQ(e.target.value)} placeholder="搜索 (关键词 / 语义)" />
          <button className="btn" onClick={doSearch} disabled={searching}>{searching ? '搜索中...' : '搜索'}</button>
          {view==='search' && <button className="btn subtle" onClick={clearSearch}>清除</button>}
        </div>
        <div className="auth-area">
          {token ? (
            <>
              <span className="user-chip" title={userId||''}>{userId || '已登录'}</span>
              <button className="btn outline" onClick={logout}>退出</button>
            </>
          ) : (
            <button className="btn primary" onClick={ensureAuthModal}>登录</button>
          )}
        </div>
      </header>
      <main className="content">
        <section className="create-panel">
          <input className="input" placeholder={token? '新任务标题' : '登录后可添加任务'} value={title} onChange={e=>setTitle(e.target.value)} disabled={!token} />
          <input className="input" placeholder="描述" value={desc} onChange={e=>setDesc(e.target.value)} disabled={!token} />
          {/* 当选择了优先级层级后隐藏手动输入分数与标签（派生自层级） */}
          {!selectedPriorityLevel && (
            <>
              <input className="input" placeholder="优先级分数(0~..)" value={prioScore} onChange={e=>setPrioScore(e.target.value)} disabled={!token} />
              <input className="input" placeholder="优先级标签(如 High/Low)" value={prioLabel} onChange={e=>setPrioLabel(e.target.value)} disabled={!token} />
            </>
          )}
          <input className="input" placeholder="分类ID" value={category} onChange={e=>setCategory(e.target.value)} disabled={!token} />
          <select className="input" value={selectedPriorityLevel} onChange={e=>setSelectedPriorityLevel(e.target.value)} disabled={!token}>
            <option value="">选择优先级层级</option>
            {priorityLevels.map(pl => <option key={pl.id} value={pl.id}>{pl.name}</option>)}
          </select>
          <div className="tags-select" style={{display:'flex', flexWrap:'wrap'}}>
            {tags.map(tag => {
              const checked = selectedTags.includes(tag.id)
              return (
                <label key={tag.id} style={{marginRight:'8px', fontSize:'12px'}}>
                  <input type="checkbox" checked={checked} onChange={()=>{
                    setSelectedTags(prev => checked ? prev.filter(i=>i!==tag.id) : [...prev, tag.id])
                  }} /> {tag.name}
                </label>
              )
            })}
          </div>
          <div className="sort-controls" style={{display:'flex', gap:'8px', alignItems:'center'}}>
            <select value={sortField} onChange={e=>{setSortField(e.target.value as any); cursor.current={}; setTodos([]); setHasMore(true);}} disabled={!token}>
              <option value="created">按创建时间</option>
              <option value="priority">按优先级</option>
            </select>
            <select value={sortOrder} onChange={e=>{setSortOrder(e.target.value as any); cursor.current={}; setTodos([]); setHasMore(true);}} disabled={!token}>
              <option value="desc">倒序</option>
              <option value="asc">正序</option>
            </select>
            <button type="button" className="btn outline" disabled={!token} onClick={()=>setShowPriorityMgr(true)}>管理优先级</button>
          </div>
          <button className="btn primary" onClick={onCreate} disabled={!token}>添加</button>
        </section>
        <section className="list-panel" onScroll={onScroll}>
          {list.map(t => (
            <div key={t.id} className={`card todo ${t.statusCode === 1 ? 'completed' : ''}`}>
              <div className="card-head">
                <h3 className="title">{t.title}</h3>
                <time className="timestamp">{new Date(t.createdAt).toLocaleString()}</time>
              </div>
              {t.description && <p className="desc">{t.description}</p>}
              <div className="desc">分类: {(() => { const c = categories.find(c=>c.id===t.categoryId); return c? c.name : '—' })()} · 优先级: {t.priorityLabel || t.priorityScore || '—'}</div>
              <div className="card-actions">
                <button className="btn" onClick={()=>onToggle(t.id, t.statusCode === 1)}>{t.statusCode === 1 ? '标记未完成' : '标记完成'}</button>
                <button className="btn danger" onClick={()=>onDelete(t.id)}>回收站</button>
              </div>
            </div>
          ))}
          {loading && view==='list' && <div className="loading">加载中...</div>}
          {view==='list' && !hasMore && <div className="end-tip">没有更多了</div>}
          {view==='search' && !searching && list.length===0 && <div className="empty">无搜索结果</div>}
        </section>
      </main>
      <footer className="app-footer">{token? '已登录，可写操作' : '未登录，只读模式'} · Keyset + 动态分页 · Hybrid 搜索</footer>
      {showTrash && (
        <div className="modal-backdrop" onClick={()=>setShowTrash(false)}>
          <div className="modal" onClick={e=>e.stopPropagation()}>
            <div className="modal-head">
              <h2>回收站</h2>
              <button className="btn subtle" onClick={()=>{ setShowTrash(false) }}>关闭</button>
            </div>
            <div className="modal-body">
              {loadingTrash && <div className="loading">加载中...</div>}
              {!loadingTrash && trash.length===0 && <div className="empty">暂无已删除任务</div>}
              {!loadingTrash && trash.map(t => (
                <div key={t.id} className="card trash-item">
                  <div className="card-head">
                    <h3 className="title">{t.title}</h3>
                    <time className="timestamp">{new Date(t.createdAt).toLocaleString()}</time>
                  </div>
                  {t.description && <p className="desc">{t.description}</p>}
                  <div className="desc">分类: {(() => { const c = categories.find(c=>c.id===t.categoryId); return c? c.name : '—' })()} · 优先级: {t.priorityLabel || t.priorityScore || '—'}</div>
                  <div className="card-actions">
                    <button className="btn" onClick={()=>onRestore(t.id)}>恢复</button>
                    <button className="btn danger" onClick={()=>onPurge(t.id)}>彻底删除</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {showPriorityMgr && (
        <div className="modal-backdrop" onClick={()=>setShowPriorityMgr(false)}>
          <div className="modal" onClick={e=>e.stopPropagation()}>
            <div className="modal-head">
              <h2>管理优先级层级</h2>
              <button className="btn subtle" onClick={()=>setShowPriorityMgr(false)}>关闭</button>
            </div>
            <div className="modal-body">
              {priorityLevels.length === 0 && <div className="empty">尚未创建任何优先级层级，可在下方新增。</div>}
              <ul className="priority-levels" style={{listStyle:'none', padding:0}}>
                {priorityLevels.map((pl,i) => (
                  <li key={pl.id} style={{display:'flex', alignItems:'center', gap:'4px', marginBottom:'6px'}}>
                    <span style={{flex:1}}>{i+1}. {pl.name}</span>
                    <button className="btn subtle" onClick={async ()=>{
                      const newName = prompt('重命名为', pl.name)
                      if (!newName || !newName.trim()) return
                      try { await fetchJson(`/api/config/priority-levels/${pl.id}`, { method:'PATCH', body: JSON.stringify({ newName }) }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                    }}>重命名</button>
                    <button className="btn subtle" disabled={i===0} onClick={async ()=>{
                      // 移动到前一项之前
                      const beforeId = priorityLevels[i-1].id
                      try { await fetchJson(`/api/config/priority-levels/${pl.id}`, { method:'PATCH', body: JSON.stringify({ moveBeforeId: beforeId }) }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                    }}>上移</button>
                    <button className="btn subtle" disabled={i===priorityLevels.length-1} onClick={async ()=>{
                      const afterId = priorityLevels[i+1].id
                      try { await fetchJson(`/api/config/priority-levels/${pl.id}`, { method:'PATCH', body: JSON.stringify({ moveAfterId: afterId }) }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                    }}>下移</button>
                    <button className="btn danger" onClick={async ()=>{
                      if (!confirm('删除该优先级层级？不会影响已有任务的分数（但新建任务无法再选择该层级）。')) return
                      try { await fetchJson(`/api/config/priority-levels/${pl.id}`, { method:'DELETE' }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                    }}>删除</button>
                    <button className="btn" title="在其后插入新层级" onClick={async ()=>{
                      const name = prompt('新层级名称')
                      if (!name || !name.trim()) return
                      try { await fetchJson('/api/config/priority-levels', { method:'POST', body: JSON.stringify({ name: name.trim(), afterId: pl.id }) }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                    }}>后插</button>
                  </li>
                ))}
              </ul>
              <div style={{marginTop:'12px'}}>
                <button className="btn primary" onClick={async ()=>{
                  const name = prompt('新优先级层级名称')
                  if (!name || !name.trim()) return
                  try { await fetchJson('/api/config/priority-levels', { method:'POST', body: JSON.stringify({ name: name.trim() }) }); refreshPriorityLevels() } catch(e){ console.warn(e) }
                }}>新增顶层</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
