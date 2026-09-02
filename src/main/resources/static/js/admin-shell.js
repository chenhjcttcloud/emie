const EMIE = window.EMIE;
const renderAdminLogs = (...args) => EMIE.actions.renderAdminLogs(...args);
const renderAdminShares = (...args) => EMIE.actions.renderAdminShares(...args);
const renderAdminCategories = (...args) => EMIE.actions.renderAdminCategories(...args);
const renderAdminIpOptions = (...args) => EMIE.actions.renderAdminIpOptions(...args);
const renderAdminCompliance = (...args) => EMIE.actions.renderAdminCompliance(...args);
const renderAdminPriceRanges = (...args) => EMIE.actions.renderAdminPriceRanges(...args);
const renderAdminOrg = (...args) => EMIE.actions.renderAdminOrg(...args);
const renderAdminRoles = (...args) => EMIE.actions.renderAdminRoles(...args);
const renderAdminScoring = (...args) => EMIE.actions.renderAdminScoring(...args);
const renderAdminPoints = (...args) => EMIE.actions.renderAdminPoints(...args);
const renderAdminFileStorage = (...args) => EMIE.actions.renderAdminFileStorage(...args);
const renderAdminUsers = (...args) => EMIE.actions.renderAdminUsers(...args);
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);
const renderAdminWorkload = (...args) => EMIE.actions.renderAdminWorkload(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// EMIE 系统管理：配置、用户、角色、业务选项、组织、日志、归档与工作量
// ==================== 后台管理 ====================

async function renderAdmin(main, role, uid) {
  main.innerHTML = `
    <div class="admin-page">
      <div class="admin-header">
        <div class="admin-header-icon">⚙</div>
        <div><span class="admin-header-eyebrow">SYSTEM CONTROL CENTER</span><h2>系统管理</h2>
        <p>集中管理系统配置、组织权限、业务规则与运行状态</p></div>
        <span class="admin-header-status"><i></i> 管理服务正常</span>
      </div>
      <div class="admin-layout"><aside class="admin-tabs" id="adminTabs">
        <div class="admin-tab-group"><div class="admin-tab-group-title">总览</div><button class="admin-tab ${EMIE.adminState.currentTab === 'dashboard' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="dashboard">📊 概览</button></div>
        <div class="admin-tab-group"><div class="admin-tab-group-title">系统与外观</div><button class="admin-tab ${EMIE.adminState.currentTab === 'config' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="config">🔧 系统配置</button><button class="admin-tab ${EMIE.adminState.currentTab === 'feishuV2' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="feishuV2">🔄 飞书 V2 同步</button><button class="admin-tab ${EMIE.adminState.currentTab === 'feishuFields' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="feishuFields">📋 飞书同步字段</button><button class="admin-tab ${EMIE.adminState.currentTab === 'appearance' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="appearance">🎨 外观</button><button class="admin-tab ${EMIE.adminState.currentTab === 'filestorage' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="filestorage">📦 文件与存储</button></div>
        <div class="admin-tab-group"><div class="admin-tab-group-title">用户与权限</div><button class="admin-tab ${EMIE.adminState.currentTab === 'users' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="users">👥 用户管理</button><button class="admin-tab ${EMIE.adminState.currentTab === 'roles' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="roles">🔐 角色与权限</button><button class="admin-tab ${EMIE.adminState.currentTab === 'org' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="org">🏢 组织架构</button></div>
        <div class="admin-tab-group"><div class="admin-tab-group-title">业务配置</div><button class="admin-tab ${EMIE.adminState.currentTab === 'categories' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="categories">📂 产品类目</button><button class="admin-tab ${EMIE.adminState.currentTab === 'ipOptions' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="ipOptions">🏷️ IP配置</button><button class="admin-tab ${EMIE.adminState.currentTab === 'compliance' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="compliance">⚖️ 合规处罚</button><button class="admin-tab ${EMIE.adminState.currentTab === 'priceRanges' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="priceRanges">💰 参考零售价</button><button class="admin-tab ${EMIE.adminState.currentTab === 'scoring' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="scoring">⭐ 评分管理</button><button class="admin-tab ${EMIE.adminState.currentTab === 'points' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="points">🏅 积分规则</button></div>
        <div class="admin-tab-group"><div class="admin-tab-group-title">通知中心</div><button class="admin-tab ${EMIE.adminState.currentTab === 'notificationCenter' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="notificationCenter">🔔 通知设置与发送记录</button><button class="admin-tab ${EMIE.adminState.currentTab === 'notificationTemplates' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="notificationTemplates">💬 通知文案</button></div>
        <div class="admin-tab-group"><div class="admin-tab-group-title">运维与审计</div><button class="admin-tab ${EMIE.adminState.currentTab === 'logs' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="logs">📜 日志</button><button class="admin-tab ${EMIE.adminState.currentTab === 'shares' ? 'active' : ''}" data-emie-action="click:admin-tab" data-tab="shares">🔗 分享管理</button></div>
      </aside><section class="admin-content-shell"><div id="adminSectionHeader"></div><main id="adminContent"></main></section></div>
    </div>`;
  await renderAdminContent();
}

async function switchAdminTab(tab) {
  const adminNav = document.getElementById('adminTabs');
  const navScrollTop = adminNav?.scrollTop || 0;
  EMIE.adminState.currentTab = tab;
  localStorage.setItem('design_pm_lastAdminTab', tab);
  const tabs = document.querySelectorAll('.admin-tab');
  tabs.forEach(t => t.classList.remove('active'));
  const activeTab = document.querySelector(`.admin-tab[data-emie-action*="admin-tab"]`);
  if (activeTab) activeTab.classList.add('active');
  await renderAdminContent();
  if (adminNav) adminNav.scrollTop = navScrollTop;
  const contentShell = document.querySelector('.admin-content-shell');
  if (contentShell) contentShell.scrollTop = 0;
}

async function renderAdminContent() {
  const container = document.getElementById('adminContent');
  if (!container) return;
  renderAdminSectionHeader();
  try {
    if (EMIE.adminState.currentTab === 'dashboard') {
      await renderAdminDashboard(container);
    } else if (EMIE.adminState.currentTab === 'notificationCenter') {
      await renderAdminNotificationCenter(container);
    } else if (EMIE.adminState.currentTab === 'config') {
      await renderAdminConfig(container);
    } else if (EMIE.adminState.currentTab === 'feishuFields') {
      await renderFeishuFieldMappings(container);
    } else if (EMIE.adminState.currentTab === 'feishuV2') {
      await renderFeishuV2(container);
    } else if (EMIE.adminState.currentTab === 'notificationTemplates') {
      await renderAdminNotificationTemplates(container);
    } else if (EMIE.adminState.currentTab === 'appearance') {
      await renderAdminAppearance(container);
    } else if (EMIE.adminState.currentTab === 'users') {
      await renderAdminUsers(container);
    } else if (EMIE.adminState.currentTab === 'roles') {
      await renderAdminRoles(container);
    } else if (EMIE.adminState.currentTab === 'categories') {
      await renderAdminCategories(container);
    } else if (EMIE.adminState.currentTab === 'ipOptions') {
      await renderAdminIpOptions(container);
    } else if (EMIE.adminState.currentTab === 'compliance') {
      await renderAdminCompliance(container);
    } else if (EMIE.adminState.currentTab === 'priceRanges') {
      await renderAdminPriceRanges(container);
    } else if (EMIE.adminState.currentTab === 'org') {
      await renderAdminOrg(container);
    } else if (EMIE.adminState.currentTab === 'scoring') {
      await renderAdminScoring(container);
    } else if (EMIE.adminState.currentTab === 'points') {
      await renderAdminPoints(container);
    } else if (EMIE.adminState.currentTab === 'logs') {
      await renderAdminLogs(container);
    } else if (EMIE.adminState.currentTab === 'shares') {
      await renderAdminShares(container);
    } else if (EMIE.adminState.currentTab === 'filestorage') {
      await renderAdminFileStorage(container);
    } else if (EMIE.adminState.currentTab === 'workload') {
      await renderAdminWorkload(container);
    }
  } catch (e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${escHtml(e.message)}</p></div>`;
  }
}

function renderAdminSectionHeader() {
  const header = document.getElementById('adminSectionHeader');
  if (!header) return;
  const meta = {
    dashboard: ['管理概览', '快速掌握系统配置、成员规模与服务运行状态', '⌂'],
    config: ['系统配置', '维护系统基础参数与外部服务连接配置', '⚙'],
    feishuFields: ['飞书同步字段', '选择要同步的系统字段，并设置对应的飞书列名', '▤'],
    feishuV2: ['飞书 V2 数据同步', '创建、核验并安全切换系统到全新飞书八表镜像', '↻'],
    appearance: ['外观设置', '统一系统名称、品牌标识与界面展示', '◐'],
    filestorage: ['文件与存储', '查看文件存储使用情况并维护归档策略', '▣'],
    users: ['用户管理', '维护成员账号、角色、状态与登录信息', '♙'],
    roles: ['角色与权限', '配置角色能力边界与数据访问范围', '◇'],
    org: ['组织架构', '维护部门、岗位及成员组织关系', '▦'],
    categories: ['产品类目', '维护项目使用的产品分类基础数据', '◫'],
    ipOptions: ['IP 配置', '维护产品项目可选择的 IP 基础选项', '◆'],
    compliance: ['合规处罚', '维护合规检查项与处罚规则', '⚖'],
    priceRanges: ['参考零售价', '维护产品项目使用的价格区间', '¥'],
    scoring: ['评分管理', '配置设计需求评分标准与评审流程', '★'],
    points: ['积分规则', '管理积分、绩效、异议、归档和接单治理', '✦'],
    notificationCenter: ['通知设置与发送记录', '配置通知渠道并查看后台投递情况', '●'],
    notificationTemplates: ['通知文案', '维护业务节点使用的通知模板', '✉'],
    logs: ['操作日志', '查询关键业务操作与账号活动记录', '≡'],
    shares: ['分享管理', '查看并维护系统生成的外部分享链接', '↗'],
    workload: ['工作量分析', '按成员和时间范围查看任务工作量', '▥'],
  }[EMIE.adminState.currentTab] || ['系统管理', '维护系统配置与管理数据', '⚙'];
  header.innerHTML = `<div class="admin-section-heading"><span>${meta[2]}</span><div><h3>${meta[0]}</h3><p>${meta[1]}</p></div><small>系统管理 / ${meta[0]}</small></div>`;
}

async function renderAdminNotificationCenter(container) {
  await renderAdminConfig(container);
  container.querySelectorAll('.config-card').forEach(card => {
    const group = card.dataset.group || '';
    if (!['notification', 'temporary-broadcast', 'notification-failures'].includes(group)) card.remove();
  });
}

// ===== Admin: 概览 =====
async function renderAdminDashboard(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  // 获取所有配置统计
  const [configs, users, sync] = await Promise.all([
    apiGet('/admin/configs'),
    apiGet('/admin/users'),
    apiGet('/admin/sync/stats'),
  ]);

  const totalConfigs = Object.values(configs).reduce((sum, arr) => sum + arr.length, 0);
  const groupCount = Object.keys(configs).length;
  const totalUsers = users.length;

  const adminUserId = EMIE.state.authUser.userId;
  const stats = [
    { icon: '🔧', label: '配置项总数', value: totalConfigs },
    { icon: '📂', label: '配置分组', value: groupCount },
    { icon: '👥', label: '用户总数', value: totalUsers },
    { icon: '👤', label: '当前管理员', value: EMIE.state.authUser.name || '未知' },
  ];

  container.innerHTML = `
    <div class="admin-dashboard-intro"><div><span>ADMIN OVERVIEW</span><h3>管理概览</h3><p>快速掌握系统配置、成员规模和关键服务状态</p></div><div class="admin-dashboard-date">${new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })}</div></div>
    <div class="admin-stats-grid">
      ${stats.map(s => `
        <div class="admin-stat-card">
          <div class="admin-stat-icon">${s.icon}</div>
          <div class="admin-stat-copy"><div class="admin-stat-label">${s.label}</div><div class="admin-stat-value">${s.value}</div></div>
        </div>
      `).join('')}
    </div>
    <div class="admin-overview-grid">
    <section class="admin-overview-card admin-sync-card">
      <div class="admin-overview-card-head">
        <div><span class="admin-card-icon blue">↻</span><div><h3>飞书同步状态</h3><p>业务数据同步队列运行情况</p></div></div>
        <span class="admin-service-state ${sync.fail > 0 ? 'is-warning' : ''}"><i></i>${sync.fail > 0 ? '存在失败任务' : '运行正常'}</span>
      </div>
      <div class="admin-sync-metrics">
        ${[['待处理', sync.pending], ['处理中', sync.processing], ['失败', sync.fail], ['已完成', sync.done]].map(([label, value], index) => `<div class="tone-${index}"><span>${label}</span><strong>${value ?? 0}</strong></div>`).join('')}
      </div>
      <div class="admin-sync-footer"><span>最近成功：${sync.lastSuccessAt || '暂无'}</span><button class="btn btn-outline btn-sm" data-emie-action="click:admin-feishu-sync">立即同步</button></div>
      ${sync.lastFailure ? `<div style="margin-top:8px;padding:10px;background:#fff1f2;border:1px solid #fecdd3;border-radius:8px;font-size:12px;color:#9f1239;">最近失败：${escHtml(sync.lastFailure.entityType || '')} #${sync.lastFailure.entityId || '-'} · ${escHtml(sync.lastFailure.error || '未知错误')} <button class="btn btn-sm btn-outline" style="margin-left:8px" data-emie-action="click:admin-retry-sync" data-queue-id="${sync.lastFailure.id}">重试此条</button></div>` : ''}
      <div id="feishuSyncResult" style="margin-top:8px;font-size:12px;color:var(--gray-500);"></div>
    </section>
    <section class="admin-overview-card admin-integrity-card">
      <div class="admin-overview-card-head">
        <div><span class="admin-card-icon green">✓</span><div><h3>数据完整性检查</h3><p>只读扫描文件引用，不修改任何数据</p></div></div>
        <button class="btn btn-outline btn-sm" data-emie-action="click:admin-integrity-scan">开始扫描</button>
      </div>
      <div class="admin-integrity-placeholder"><strong>系统文件引用检查</strong><span>建议在数据迁移或批量导入后执行</span></div>
      <div id="dataIntegrityResult"></div>
    </section></div>
    <section class="admin-overview-card admin-config-overview">
      <div class="admin-overview-card-head"><div><span class="admin-card-icon purple">⌘</span><div><h3>配置分组概览</h3><p>按模块查看当前生效的系统配置数量</p></div></div></div>
      <div class="admin-config-group-grid">
        ${Object.entries(configs).map(([group, items]) => `
          <div class="admin-config-group-card">
            <div>${groupLabel(group)}</div><strong>${items.length}</strong><span>个配置项</span>
          </div>
        `).join('')}
      </div>
    </section>`;
}

async function triggerFeishuSync(button) {
  const result = document.getElementById('feishuSyncResult');
  button.disabled = true;
  button.textContent = '同步中…';
  if (result) result.textContent = '正在处理待同步队列，请稍候…';
  let poller;
  const refreshProgress = async () => {
    try {
      const stats = await apiGet('/admin/sync/stats');
      if (!result) return;
      result.textContent = `同步处理中：待处理 ${stats.pending ?? 0} 条，处理中 ${stats.processing ?? 0} 条，失败 ${stats.fail ?? 0} 条，已完成 ${stats.done ?? 0} 条。`;
    } catch (_) {
      // 同步请求本身仍继续，进度刷新失败不影响后台任务。
    }
  };
  await refreshProgress();
  poller = setInterval(refreshProgress, 2000);
  try {
    const payload = await apiPost('/admin/sync/process', {});
    await refreshProgress();
    if (result) result.textContent += ` ${payload.message || '本轮同步处理完成'}。`;
  } catch (e) {
    if (result) result.textContent = `同步失败：${e.message || '未知错误'}`;
  } finally {
    clearInterval(poller);
    button.disabled = false;
    button.textContent = '立即同步';
  }
}

async function runDataIntegrityScan(button) {
  const result = document.getElementById('dataIntegrityResult');
  if (!result) return;
  button.disabled = true;
  button.textContent = '扫描中…';
  result.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  const startedAt = Date.now();
  let progress = 8;
  result.innerHTML = '<div style="padding:10px 12px;border:1px solid #C7D2FE;border-radius:10px;background:#F5F7FF;font-size:12px;color:var(--gray-700);"><strong>正在扫描数据完整性…</strong> <span id="dataIntegrityElapsed">0 秒</span><div style="height:9px;background:#E5E7EB;border-radius:999px;overflow:hidden;margin-top:9px;"><div id="dataIntegrityProgress" style="height:100%;width:8%;background:var(--primary);border-radius:999px;transition:width .4s ease;"></div></div><div style="font-size:11px;color:var(--gray-500);margin-top:6px;">正在检查项目、子任务和设计需求文件引用，请稍候。</div></div>';
  const ticker = setInterval(() => {
    progress = Math.min(92, progress + (progress < 60 ? 7 : 2));
    const bar = document.getElementById('dataIntegrityProgress');
    const elapsed = document.getElementById('dataIntegrityElapsed');
    if (bar) bar.style.width = `${progress}%`;
    if (elapsed) elapsed.textContent = `${Math.max(1, Math.round((Date.now() - startedAt) / 1000))} 秒`;
  }, 500);
  try {
    const report = await apiGet('/system/data-integrity');
    const missing = report.missingFiles || [];
    const invalid = report.invalidJson || [];
    const duplicates = report.duplicateReferences || [];
    const issues = [...missing.map(item => `缺失文件：${item}`), ...invalid.map(item => `JSON异常：${item}`), ...duplicates.map(item => `重复引用：${item}`)];
    result.innerHTML = `<div style="font-size:13px;color:${report.healthy ? 'var(--success)' : 'var(--danger)'};font-weight:600;">${report.healthy ? '检查通过，未发现缺失文件或 JSON 异常。' : `发现 ${issues.length} 项问题。`}</div><div style="font-size:11px;color:var(--gray-400);margin-top:4px;">已扫描 ${report.scannedFiles ?? 0} 个文件，耗时 ${Math.max(1, Math.round((Date.now() - startedAt) / 1000))} 秒</div>${issues.length ? `<div style="margin-top:8px;max-height:180px;overflow:auto;background:#fff7ed;border:1px solid #fed7aa;border-radius:8px;padding:10px;font-size:12px;color:#9a3412;">${issues.slice(0, 100).map(item => `<div style="margin:3px 0;">${escHtml(item)}</div>`).join('')}</div>` : ''}`;
  } catch (e) {
    result.innerHTML = `<div style="font-size:12px;color:var(--danger);">扫描失败：${escHtml(e.message || '未知错误')}</div>`;
  } finally {
    clearInterval(ticker);
    button.disabled = false;
    button.textContent = '重新扫描';
  }
}

function groupLabel(group) {
  return { appearance: '🎨 外观设置', security: '🔒 安全设置', system: '💻 系统信息', feishu: '💬 飞书 SSO 登录', nas: '🗄️ NAS 归档', feishu_base: '📊 飞书多维表格', notification: '🔔 通知中心', notification_templates: '💬 飞书通知模板', points: '🏅 积分设置' }[group] || group;
}

function configLabel(key) {
  const labels = {
    'notification.publicBaseUrl': '飞书通知跳转地址',
    'notification.enabled': '启用通知中心',
    'notification.inAppEnabled': '启用站内通知',
    'notification.feishuEnabled': '启用飞书通知',
    'system.dbType': '数据库类型',
    'system.dbUrl': '数据库连接地址',
    'system.version': '系统版本号',
    'system.fileUploadMaxSize': '文件上传最大限制（MB）',
  };
  return labels[key] || key.split('.').pop();
}

// ===== Admin: 系统配置 =====
async function renderAdminConfig(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  const configs = await apiGet('/admin/configs');

  let html = '';
  for (const [group, items] of Object.entries(configs)) {
    if (group === 'appearance' || group === 'security' || group === 'notification_templates' || (group === 'notification' && EMIE.adminState.currentTab === 'config')) continue; // 独立页面管理；登录统一由飞书 SSO 负责
    html += `
      <div class="config-card" data-group="${group}">
        <div class="config-card-header">
          <h3>${groupLabel(group)}</h3>
          ${group === 'notification' ? '<button class="btn btn-sm btn-secondary" data-emie-action="click:admin-test-notification">🧪 保存并发送测试</button>' : ''}
          <button class="btn btn-sm btn-primary" data-emie-action="click:admin-save-config" data-config-group="${group}">💾 保存</button>
        </div>
        <div class="config-card-body">
          <div class="config-grid">
            ${items.filter(item => item.configKey !== 'feishu.base.fieldMappings').map(item => `
              <div class="config-item ${item.valueType === 'text' && (item.configValue || '').length > 60 ? 'full' : ''}">
                <label>${configLabel(item.configKey)}</label>
                <span class="config-desc">${escHtml(item.description || '')}</span>
                ${item.valueType === 'password'
                  ? `<input type="password" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}" autocomplete="off">`
                  : item.valueType === 'textarea'
                    ? `<textarea class="config-input" data-key="${item.configKey}" rows="3" placeholder="${escHtml(item.description || '')}">${escHtml(item.configValue || '')}</textarea>`
                  : item.valueType === 'number'
                    ? `<input type="number" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}">`
                    : item.valueType === 'boolean'
                      ? `<select class="config-input" data-key="${item.configKey}">
                           <option value="true" ${item.configValue === 'true' ? 'selected' : ''}>开</option>
                           <option value="false" ${item.configValue === 'false' ? 'selected' : ''}>关</option>
                         </select>`
                      : `<input type="text" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}">`
                }
              </div>
            `).join('')}
          </div>
        </div>
      </div>`;
  }

  container.innerHTML = html;
  if (configs.notification && EMIE.adminState.currentTab === 'notificationCenter') {
    container.insertAdjacentHTML('beforeend', `<div class="config-card" data-group="temporary-broadcast">
      <div class="config-card-header"><h3>📣 系统更新通知</h3><button class="btn btn-primary" id="temporaryBroadcastSendButton" data-emie-action="click:admin-temp-broadcast">发送给全部用户</button></div>
      <div class="config-card-body">
        <p style="margin:0 0 6px;color:var(--gray-700);font-size:13px;">编辑完成后，将向除已停用账号外的所有系统用户发送飞书通知。</p>
        <p style="margin:0 0 12px;color:var(--gray-500);font-size:12px;">未绑定飞书的用户无法投递，并会计入发送结果；本次内容不会保存为通知模板。</p>
        <input class="form-input" id="temporaryBroadcastTitle" placeholder="通知标题，例如：系统更新安排" maxlength="100" style="margin-bottom:8px;">
        <textarea class="form-textarea" id="temporaryBroadcastContent" placeholder="请输入更新时间、影响范围和注意事项" maxlength="2000" rows="5"></textarea>
      </div></div>`);
    container.insertAdjacentHTML('beforeend', `<div class="config-card" data-group="notification-failures">
      <div class="config-card-header"><h3>🛠️ 通知发送记录（仅管理员）</h3><button class="btn btn-sm btn-secondary" data-emie-action="click:admin-notification-refresh">🔄 刷新</button></div>
      <div class="config-card-body" id="notificationFailures"><div class="loading">加载中</div></div>
    </div>`);
    await loadNotificationFailures();
  }
}

const FEISHU_FIELD_CATALOG = {
  project: { label: '项目总表', identity: '项目ID', fields: [
    ['项目ID','文本'],['项目编号','文本'],['项目名称','文本'],['产品名称','文本'],['类型','文本'],['状态','文本'],['销售','文本'],['产品企划','文本'],['截止日期','日期'],['产品类目','文本'],['参考价格','文本'],['子任务数','数字'],['完成进度','数字/百分比'],['项目流程','文本'],['子任务流程','文本'],['当前审核阶段','文本'],['审核进度','数字/百分比'],['创建时间','日期'],['项目描述','文本'],['同步来源','文本'],['目标市场','文本'],['合规项','文本'],['IP名称','文本'],['创意作者','文本'],['来源','文本'],['产品类目备注','文本'],['需求说明','文本']
  ]},
  task: { label: '子任务表', identity: '子任务ID', fields: [
    ['子任务ID','文本'],['任务名称','文本'],['状态','文本'],['负责人','文本'],['计划日期','日期'],['实际完成日期','日期'],['自评分','数字'],['所属项目','关联/文本'],['一审角色','文本'],['一审状态','文本'],['一审得分','数字'],['一审审核人','文本'],['二审角色','文本'],['二审状态','文本'],['二审得分','数字'],['二审审核人','文本'],['审核得分','数字'],['创建时间','日期'],['所属阶段','文本'],['负责人类型','文本'],['发布人','文本'],['细节要求说明','文本'],['交付成果','文本'],['审核意见','文本'],['同步来源','文本']
  ]},
  scoring: { label: '评分记录表', identity: '评分ID', fields: [
    ['评分ID','文本'],['评分角色','文本'],['评分','数字'],['权重','数字'],['项目ID','文本'],['项目类型','文本'],['审核阶段','文本'],['审核状态','文本'],['审核人','文本'],['审核意见','文本'],['同步来源','文本'],['所属子任务','关联/文本']
  ]},
  log: { label: '操作日志表', identity: '日志ID', fields: [
    ['日志ID','文本'],['操作内容','文本'],['操作人','文本'],['角色','文本'],['所属项目','关联/文本'],['时间','日期'],['同步来源','文本']
  ]}
};

async function renderFeishuFieldMappings(container) {
  container.innerHTML = '<div class="loading">加载中</div>';
  const configs = await apiGet('/admin/configs');
  const item = (configs.feishu_base || []).find(row => row.configKey === 'feishu.base.fieldMappings');
  let saved = {};
  try { saved = JSON.parse(item?.configValue || '{}'); } catch (_) { saved = {}; }
  container.innerHTML = `<div class="config-card"><div class="config-card-header"><div><h3>📋 同步字段配置</h3><p style="margin:5px 0 0;color:var(--gray-500);font-size:12px;">关闭的字段不会传到飞书；目标列不存在时系统会自动创建。非关键类型冲突会写入“系统文本/数字/日期”兼容列，不修改人工列。</p></div><div><button class="btn btn-sm btn-secondary" data-emie-action="click:admin-diagnose-feishu-fields">结构预检</button> <button class="btn btn-sm btn-primary" data-emie-action="click:admin-save-feishu-fields">💾 保存字段配置</button></div></div><div class="config-card-body"><div id="feishuSchemaDiagnostics" style="margin-bottom:12px"></div>
    ${Object.entries(FEISHU_FIELD_CATALOG).map(([key, table]) => `<div style="margin-bottom:24px"><h4 style="margin:0 0 10px">${table.label}</h4><div style="overflow:auto"><table class="data-table"><thead><tr><th style="width:80px">同步</th><th>系统字段</th><th>飞书目标列名</th><th style="width:120px">字段类型</th></tr></thead><tbody>${table.fields.map(([name,type]) => { const cfg = saved[key]?.[name] || {}; const locked = name === table.identity; return `<tr><td><input type="checkbox" class="feishu-field-enabled" data-table="${key}" data-source="${name}" ${cfg.enabled !== false || locked ? 'checked' : ''} ${locked ? 'disabled' : ''}></td><td>${name}${locked ? ' <span style="color:var(--gray-400)">（必需）</span>' : ''}</td><td><input class="config-input feishu-field-target" data-table="${key}" data-source="${name}" value="${escHtml(locked ? name : (cfg.target || name))}" ${locked ? 'disabled' : ''}></td><td>${type}</td></tr>`; }).join('')}</tbody></table></div></div>`).join('')}
  </div></div>`;
}

async function renderFeishuV2(container) {
  const [v2, sync] = await Promise.all([
    apiGet('/admin/sync/v2/status'),
    apiGet('/admin/sync/stats'),
  ]);
  const tables = Array.isArray(v2.tables) ? v2.tables : [];
  const readyCount = tables.filter(item => item.configured).length;
  const activeLabel = v2.active ? 'V2 已激活' : '旧版同步运行中';
  const activeTone = v2.active ? '#047857' : '#92400e';
  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header"><div><h3>🔄 飞书 V2 数据镜像</h3><p style="margin:5px 0 0;color:var(--gray-500);font-size:12px;">系统单向同步至飞书；四张主表全量对账，四张备份表仅随业务变更增量保留。</p></div><button class="btn btn-sm btn-outline" data-emie-action="click:admin-feishu-v2-refresh">刷新状态</button></div>
      <div class="config-card-body">
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:18px;">
          <div style="padding:14px;border:1px solid var(--gray-200);border-radius:10px;"><span style="font-size:12px;color:var(--gray-500);">当前模式</span><strong style="display:block;margin-top:5px;color:${activeTone};">${activeLabel}</strong></div>
          <div style="padding:14px;border:1px solid var(--gray-200);border-radius:10px;"><span style="font-size:12px;color:var(--gray-500);">V2 表结构</span><strong style="display:block;margin-top:5px;">${readyCount} / 8</strong></div>
          <div style="padding:14px;border:1px solid var(--gray-200);border-radius:10px;"><span style="font-size:12px;color:var(--gray-500);">待处理 / 失败</span><strong style="display:block;margin-top:5px;">${sync.pending || 0} / <span style="color:${sync.fail ? '#b91c1c' : 'inherit'}">${sync.fail || 0}</span></strong></div>
          <div style="padding:14px;border:1px solid var(--gray-200);border-radius:10px;"><span style="font-size:12px;color:var(--gray-500);">回滚保护</span><strong style="display:block;margin-top:5px;">${v2.rollbackAvailable ? '可回滚旧 Base' : '尚未生成'}</strong></div>
        </div>
        <div style="overflow:auto;margin-bottom:18px;"><table class="data-table"><thead><tr><th>数据表</th><th style="width:120px;">配置状态</th></tr></thead><tbody>${tables.map(item => `<tr><td>${escHtml(item.name)}</td><td>${item.configured ? '<span style="color:#047857;">✓ 已创建</span>' : '<span style="color:#92400e;">待创建</span>'}</td></tr>`).join('')}</tbody></table></div>
        ${sync.lastFailure ? `<div style="padding:11px 13px;margin-bottom:14px;background:#fff1f2;border:1px solid #fecdd3;border-radius:8px;color:#9f1239;font-size:12px;">最近失败：${escHtml(sync.lastFailure.entityType || '')} #${sync.lastFailure.entityId || '-'} · ${escHtml(sync.lastFailure.error || '未知错误')} <button class="btn btn-sm btn-outline" style="margin-left:8px" data-emie-action="click:admin-retry-sync" data-queue-id="${sync.lastFailure.id}">重试此条</button></div>` : ''}
        <div style="display:flex;flex-wrap:wrap;gap:10px;">
          <button class="btn btn-secondary" data-emie-action="click:admin-feishu-v2-prepare" ${v2.active ? 'disabled' : ''}>① 创建并检查 V2 Base</button>
          <button class="btn btn-primary" data-emie-action="click:admin-feishu-v2-activate" ${!v2.activatable ? 'disabled' : ''}>② 激活并全量同步</button>
          <button class="btn btn-outline" data-emie-action="click:admin-feishu-sync">处理一轮队列</button>
          <button class="btn btn-outline" style="color:#b91c1c;border-color:#fecaca;" data-emie-action="click:admin-feishu-v2-rollback" ${!v2.active || !v2.rollbackAvailable ? 'disabled' : ''}>回滚旧 Base</button>
        </div>
        <p style="margin:14px 0 0;color:var(--gray-500);font-size:12px;line-height:1.7;">激活会先校验八张表，然后切换配置并自动将系统现有数据全量入队。失败会自动恢复旧 Base。旧表不会在此页面自动删除。</p>
      </div>
    </div>`;
}

async function postAdminLong(url) {
  const token = localStorage.getItem('design_pm_token');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 180000);
  try {
    const response = await fetch(`${API}${url}`, {
      method: 'POST', signal: controller.signal,
      headers: token ? { 'Content-Type': 'application/json', 'X-Auth-Token': token } : { 'Content-Type': 'application/json' },
      body: '{}',
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `请求失败 (${response.status})`);
    return payload;
  } finally { clearTimeout(timer); }
}

async function prepareFeishuV2(button) {
  if (!await EMIE.actions.showSystemConfirm('将创建一套全新的飞书 Base 和 8 张数据表，不会切换当前同步。确认继续吗？')) return;
  button.disabled = true; button.textContent = '正在创建字段，请稍候…';
  try { await postAdminLong('/admin/sync/v2/prepare'); showAdminToast('✅ V2 Base 与八张表准备完成', 'success'); await renderAdminContent(); }
  catch (e) { button.disabled = false; button.textContent = '① 创建并检查 V2 Base'; EMIE.actions.showSystemAlert('V2 Base 创建失败：' + e.message); }
}

async function activateFeishuV2(button) {
  if (!await EMIE.actions.showSystemConfirm('确认激活飞书 V2？\n\n系统会切换到新 Base 并将现有项目、子任务、评分和日志全量入队。若初始化失败会自动回滚。')) return;
  button.disabled = true; button.textContent = '正在激活并入队…';
  try { await postAdminLong('/admin/sync/v2/activate'); showAdminToast('✅ V2 已激活，全量同步正在后台执行', 'success'); await renderAdminContent(); }
  catch (e) { button.disabled = false; button.textContent = '② 激活并全量同步'; EMIE.actions.showSystemAlert('V2 激活失败：' + e.message); await renderAdminContent(); }
}

async function rollbackFeishuV2(button) {
  if (!await EMIE.actions.showSystemConfirm('确认回滚到旧飞书 Base？\n\nV2 Base 和其中的数据会保留，但后续同步将重新写入旧 Base。')) return;
  button.disabled = true;
  try { await postAdminLong('/admin/sync/v2/rollback'); showAdminToast('已回滚到旧飞书 Base', 'success'); await renderAdminContent(); }
  catch (e) { button.disabled = false; EMIE.actions.showSystemAlert('回滚失败：' + e.message); }
}

async function diagnoseFeishuFields() {
  const box = document.getElementById('feishuSchemaDiagnostics');
  if (box) box.innerHTML = '<div class="loading">正在读取飞书字段结构…</div>';
  try {
    const report = await apiGet('/admin/sync/schema-diagnostics');
    const issues = (report.tables || []).flatMap(table => (table.issues || []).map(issue => ({ ...issue, table })));
    if (!box) return;
    box.innerHTML = `<div style="padding:10px;border:1px solid ${report.valid ? '#bbf7d0' : '#fecaca'};background:${report.valid ? '#f0fdf4' : '#fff1f2'};border-radius:8px;font-size:12px;"><strong>${report.valid ? '关键字段结构可安全同步' : `存在 ${report.blockingConflicts} 个阻断冲突`}</strong> · ${report.fallbackFields || 0} 个字段将使用兼容列${issues.length ? `<div style="margin-top:7px">${issues.map(({ table, source, target, actualType, severity, fallback }) => `<div>${table.table}${table.backup ? '备份表' : '主表'}：${escHtml(source)} → ${escHtml(target)}（实际类型 ${actualType}）${severity === 'fallback' ? `，写入 ${escHtml(fallback)}` : '，必须修复'}</div>`).join('')}</div>` : ''}</div>`;
  } catch (e) {
    if (box) box.innerHTML = `<div style="color:var(--danger);font-size:12px;">预检失败：${escHtml(e.message || '未知错误')}</div>`;
  }
}

async function retrySyncQueue(queueId) {
  try {
    await apiPost(`/admin/sync/queue/${queueId}/retry`, {});
    showAdminToast('✅ 已重新入队，将在下一轮同步处理', 'success');
    await renderAdminDashboard(document.getElementById('adminContent'));
  } catch (e) { showAdminToast('❌ 重试失败：' + e.message, 'error'); }
}

async function saveFeishuFieldMappings() {
  const mappings = {};
  for (const [key, table] of Object.entries(FEISHU_FIELD_CATALOG)) {
    mappings[key] = {};
    const targets = new Set();
    for (const [name] of table.fields) {
      const enabled = name === table.identity || document.querySelector(`.feishu-field-enabled[data-table="${key}"][data-source="${name}"]`)?.checked;
      const target = name === table.identity ? name : (document.querySelector(`.feishu-field-target[data-table="${key}"][data-source="${name}"]`)?.value || '').trim();
      if (enabled && !target) { showAdminToast(`❌ ${table.label}的“${name}”目标列名不能为空`, 'error'); return; }
      if (enabled && targets.has(target)) { showAdminToast(`❌ ${table.label}存在重复目标列：${target}`, 'error'); return; }
      if (enabled) targets.add(target);
      mappings[key][name] = { enabled, target: target || name };
    }
  }
  try {
    await apiPut('/admin/configs', { configs: { 'feishu.base.fieldMappings': JSON.stringify(mappings) } });
    showAdminToast('✅ 飞书同步字段配置已保存', 'success');
  } catch (e) { showAdminToast('❌ 保存失败: ' + e.message, 'error'); }
}

async function sendTemporaryBroadcast() {
  const title = document.getElementById('temporaryBroadcastTitle')?.value?.trim();
  const content = document.getElementById('temporaryBroadcastContent')?.value?.trim();
  if (!title || !content) { showAdminToast('❌ 请填写通知标题和内容', 'error'); return; }
  if (!await EMIE.actions.showSystemConfirm('确认发送给全部系统用户？\n\n发送后，已绑定飞书的用户将立即收到这条通知。')) return;
  const button = document.getElementById('temporaryBroadcastSendButton');
  if (button) { button.disabled = true; button.textContent = '正在后台发送…'; }
  try {
    const job = await apiPost('/admin/notifications/temporary-broadcast', { title, content });
    showAdminToast('📨 已提交后台发送，可停留此页查看最终结果', 'success');
    const result = await waitForTemporaryBroadcast(job.jobId);
    showAdminToast(`✅ 发送完成：成功 ${result.delivered}，失败 ${result.failed}，未绑定飞书 ${result.unbound}`, 'success');
    document.getElementById('temporaryBroadcastTitle').value = '';
    document.getElementById('temporaryBroadcastContent').value = '';
  } catch (e) {
    showAdminToast('❌ ' + e.message, 'error');
  } finally {
    if (button) { button.disabled = false; button.textContent = '发送给全部用户'; }
  }
}

async function waitForTemporaryBroadcast(jobId) {
  if (!jobId) throw new Error('后台发送任务创建失败');
  for (let attempt = 0; attempt < 600; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    const job = await apiGet(`/admin/notifications/temporary-broadcast/${encodeURIComponent(jobId)}`);
    if (job.status === 'completed') return job.result;
    if (job.status === 'failed') throw new Error(`后台发送失败：${job.error || '未知错误'}`);
  }
  throw new Error('后台发送仍在继续，请稍后查看飞书失败通知记录');
}

let notificationFailuresRows = [];
let notificationFailuresPage = 1;
const NOTIFICATION_FAILURES_PAGE_SIZE = 15;

async function loadNotificationFailures(page = notificationFailuresPage) {
  const target = document.getElementById('notificationFailures');
  if (!target) return;
  try {
    if (!notificationFailuresRows.length || page === 1) notificationFailuresRows = await apiGet('/admin/notifications/failures');
    notificationFailuresPage = Math.max(1, Math.min(page, Math.max(1, Math.ceil(notificationFailuresRows.length / NOTIFICATION_FAILURES_PAGE_SIZE))));
    const rows = notificationFailuresRows.slice((notificationFailuresPage - 1) * NOTIFICATION_FAILURES_PAGE_SIZE, notificationFailuresPage * NOTIFICATION_FAILURES_PAGE_SIZE);
    const fmt = value => value ? new Date(value).toLocaleString('zh-CN', {hour12:false}) : '—';
    const totalPages = Math.max(1, Math.ceil(notificationFailuresRows.length / NOTIFICATION_FAILURES_PAGE_SIZE));
    target.innerHTML = notificationFailuresRows.length ? `<div class="table-responsive"><table class="data-table"><thead><tr><th>结果</th><th>业务流程</th><th>收件人</th><th>触发时间</th><th>最近尝试</th><th>发送内容</th><th>失败原因</th><th>操作</th></tr></thead><tbody>${rows.map(r => `<tr><td>${escHtml(r.statusLabel || r.status || '')}<br><small>重试 ${r.retryCount ?? 0} 次</small></td><td>${escHtml(r.processLabel || '系统通知')}</td><td>${escHtml(r.recipientName ? `${r.recipientName}（${r.recipientUserId || ''}）` : (r.recipientUserId || ''))}</td><td>${fmt(r.createdAt)}</td><td>${fmt(r.lastAttemptAt || r.firstAttemptAt)}${r.deliveredAt ? `<br>成功：${fmt(r.deliveredAt)}` : ''}${r.nextRetryAt ? `<br>下次：${fmt(r.nextRetryAt)}` : ''}</td><td style="max-width:320px;white-space:pre-wrap;">${escHtml(r.content || '')}</td><td>${escHtml(r.errorMsg || '—')}</td><td>${['failed','dead_letter','blocked'].includes(r.status) ? `<button class="btn btn-sm btn-primary" data-emie-action="click:admin-retry-notification" data-delivery-id="${r.deliveryId}">重新发送</button>` : '—'}</td></tr>`).join('')}</tbody></table></div><div class="admin-pagination"><span>共 ${notificationFailuresRows.length} 条 · 第 ${notificationFailuresPage}/${totalPages} 页</span><button class="btn btn-sm btn-secondary" ${notificationFailuresPage <= 1 ? 'disabled' : ''} data-emie-action="click:admin-notification-page" data-page="${notificationFailuresPage - 1}">上一页</button><button class="btn btn-sm btn-secondary" ${notificationFailuresPage >= totalPages ? 'disabled' : ''} data-emie-action="click:admin-notification-page" data-page="${notificationFailuresPage + 1}">下一页</button></div>` : '<div class="empty-state">暂无通知发送记录</div>';
  } catch (e) { target.innerHTML = `<div class="error-state">加载失败：${escHtml(e.message)}</div>`; }
}

async function retryNotificationDelivery(id) {
  try { await apiPost(`/admin/notifications/deliveries/${id}/retry`, {}); showAdminToast('✅ 已重新排队，系统将在下一轮发送', 'success'); notificationFailuresRows = []; await loadNotificationFailures(1); }
  catch (e) { showAdminToast('❌ 重试失败：' + e.message, 'error'); }
}

async function saveConfigGroup(group) {
  const card = document.querySelector(`.config-card[data-group="${group}"]`);
  if (!card) return;
  const inputs = card.querySelectorAll('.config-input');
  const configs = {};
  inputs.forEach(inp => {
    configs[inp.dataset.key] = inp.value;
  });

  try {
    await apiPut('/admin/configs', { configs });
    showAdminToast('✅ 配置已保存', 'success');
  } catch (e) {
    showAdminToast('❌ 保存失败: ' + e.message, 'error');
  }
}

async function sendNotificationTest() {
  const card = document.querySelector('.config-card[data-group="notification"]');
  if (!card) return;
  const configs = {};
  card.querySelectorAll('.config-input').forEach(inp => { configs[inp.dataset.key] = inp.value; });
  try {
    await apiPut('/admin/configs', { configs });
    const result = await apiPost('/admin/notifications/test', {});
    showAdminToast('✅ ' + result.message, 'success');
  } catch (e) {
    showAdminToast('❌ 测试失败: ' + e.message, 'error');
  }
}

const NOTIFICATION_TEMPLATE_SCENARIOS = [
  ['PROJECT_ASSIGNED', '项目指派', '项目被指定给产品企划时'],
  ['MATERIAL_MARKET_PLANNER_PENDING', '素材广场待企划接单', '销售从素材广场选材并生成待认领渠道定制单时'],
  ['TASK_ASSIGNED', '子任务派发', '子任务首次指派给负责人时'],
  ['TASK_REASSIGNED', '子任务改派', '子任务改派给新负责人时'],
  ['TASK_ACCEPTED', '子任务接单', '负责人确认接单时'],
  ['TASK_DELIVERED', '首次交付', '负责人提交成果、等待审核时'],
  ['TASK_REJECTED', '驳回修改', '审核人驳回成果时'],
  ['TASK_REDELIVERED', '再次交付', '驳回后再次提交成果时'],
  ['REVIEW_PENDING', '审核待办', '有审核任务等待处理时'],
  ['REVIEW_APPROVED', '审核通过', '审核通过时'],
  ['REVIEW_REJECTED', '审核驳回', '审核不通过时'],
  ['PROJECT_REMINDER', '项目催办', '项目负责人被催办时'],
  ['TASK_REMINDER', '子任务催办', '子任务负责人被催办时'],
  ['TASK_DUE_SOON', '即将到期', '子任务临近截止日期时'],
  ['TASK_OVERDUE', '任务逾期', '子任务超过截止日期时'],
  ['SYSTEM_ALERT', '系统告警', '系统需要人工关注时'],
];

const NOTIFICATION_VARIABLES = [
  ['项目名称', '{{projectName}}'], ['任务名称', '{{taskName}}'], ['操作人', '{{actorName}}'],
  ['截止时间', '{{deadline}}'], ['驳回原因', '{{reason}}'], ['交付次数', '{{deliveryCount}}'],
  ['审核角色', '{{reviewRole}}'], ['负责人', '{{targetName}}'], ['告警内容', '{{message}}'],
];

async function renderAdminNotificationTemplates(container) {
  container.innerHTML = '<div class="loading">加载中</div>';
  const groups = await apiGet('/admin/configs');
  const configs = Object.fromEntries((groups.notification_templates || []).map(item => [item.configKey, item.configValue || '']));
  const variableButtons = (event, field) => NOTIFICATION_VARIABLES.map(([label, token]) =>
    `<button type="button" class="btn btn-outline btn-sm" style="padding:3px 7px;font-size:11px;" data-emie-action="click:admin-insert-variable" data-event="${event}" data-field="${field}" data-token="${token}">＋${label}</button>`).join('');
  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header"><h3>💬 飞书通知文案</h3><button class="btn btn-primary" data-emie-action="click:admin-save-templates">💾 保存全部文案</button></div>
      <div class="config-card-body">
        <div style="padding:12px 14px;background:#EFF6FF;border:1px solid #BFDBFE;border-radius:8px;color:#1E40AF;font-size:13px;line-height:1.7;margin-bottom:18px;">直接修改成你希望员工看到的中文即可。需要自动带入项目或任务信息时，点击下方蓝色按钮插入，不需要记任何技术写法。保存后，下一条飞书通知会立即使用新文案。</div>
        ${NOTIFICATION_TEMPLATE_SCENARIOS.map(([event, label, when]) => {
          const prefix = `notification.template.${event}`;
          return `<div style="border:1px solid var(--gray-200);border-radius:10px;padding:16px;margin-bottom:12px;background:#fff;">
            <div style="font-size:15px;font-weight:650;color:var(--gray-800);margin-bottom:4px;">${escHtml(label)}</div>
            <div style="font-size:12px;color:var(--gray-500);margin-bottom:12px;">发送时机：${escHtml(when)}</div>
            <div class="form-group" style="margin-bottom:12px;"><label class="form-label">通知标题</label><input class="form-input notification-template-input" data-key="${prefix}.title" id="notificationTemplateTitle_${event}" value="${escHtml(configs[`${prefix}.title`] || '')}" maxlength="100"></div>
            <div class="form-group" style="margin-bottom:8px;"><label class="form-label">通知正文</label><textarea class="form-textarea notification-template-input" data-key="${prefix}.content" id="notificationTemplateContent_${event}" rows="3">${escHtml(configs[`${prefix}.content`] || '')}</textarea></div>
            <div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;"><span style="font-size:12px;color:var(--gray-500);margin-right:2px;">点击插入：</span>${variableButtons(event, 'content')}</div>
          </div>`;
        }).join('')}
      </div>
    </div>`;
}

function insertNotificationVariable(event, field, token) {
  const input = document.getElementById(`notificationTemplate${field === 'title' ? 'Title' : 'Content'}_${event}`);
  if (!input) return;
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  input.value = input.value.slice(0, start) + token + input.value.slice(end);
  input.focus();
  input.setSelectionRange(start + token.length, start + token.length);
}

async function saveNotificationTemplates() {
  const configs = {};
  document.querySelectorAll('.notification-template-input').forEach(input => { configs[input.dataset.key] = input.value.trim(); });
  try {
    await apiPut('/admin/configs', { configs });
    showAdminToast('✅ 飞书通知文案已保存，下一条通知将使用新文案', 'success');
  } catch (e) {
    showAdminToast('❌ 保存失败：' + e.message, 'error');
  }
}

// ===== Admin: 外观设置 =====
async function renderAdminAppearance(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  const configs = await apiGet('/admin/configs');
  const appearanceItems = configs['appearance'] || [];

  const getVal = (key) => {
    const item = appearanceItems.find(i => i.configKey === key);
    return item ? (item.configValue || '') : '';
  };

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>🎨 外观设置</h3>
        <button class="btn btn-sm btn-primary" data-emie-action="click:admin-save-appearance">💾 保存</button>
      </div>
      <div class="config-card-body">
        <div class="config-grid">
          <div class="config-item full">
            <label>系统标题</label>
            <span class="config-desc">显示在浏览器标签和页面头部</span>
            <input type="text" class="config-input" data-key="app.title" value="${escHtml(getVal('app.title'))}" placeholder="系统标题">
          </div>
          <div class="config-item full">
            <label>系统副标题</label>
            <span class="config-desc">显示在页面头部和登录页</span>
            <input type="text" class="config-input" data-key="app.subtitle" value="${escHtml(getVal('app.subtitle'))}" placeholder="系统副标题">
          </div>
          <div class="config-item full">
            <label>Logo Emoji 备用</label>
            <span class="config-desc">未上传图片 Logo 时显示此 Emoji</span>
            <input type="text" class="config-input" data-key="app.logoEmoji" value="${escHtml(getVal('app.logoEmoji'))}" placeholder="🎨" style="font-size:24px;text-align:center;max-width:80px;">
          </div>
          <div class="config-item full">
            <label>Logo 图片</label>
            <span class="config-desc">上传 Logo 图片（PNG/JPG/SVG，推荐 120x120px）</span>
            <div class="admin-image-upload">
              ${getVal('app.logo') ? `<img src="${getVal('app.logo')}" class="admin-image-preview logo-preview" id="logoPreviewImg">` : `<div class="admin-image-preview logo-preview" id="logoPreviewPlaceholder" style="display:flex;align-items:center;justify-content:center;font-size:24px;background:var(--gray-100);">${getVal('app.logoEmoji') || '🎨'}</div>`}
              <div class="admin-image-upload-btn" data-emie-action="click:admin-logo-input">
                📁 ${getVal('app.logo') ? '更换 Logo' : '上传 Logo'}
                <input type="file" id="logoUploadInput" accept="image/png,image/jpeg,image/gif,image/svg+xml,image/webp" style="display:none" data-emie-action="change:admin-logo-upload">
              </div>
              ${getVal('app.logo') ? `<button class="btn btn-sm btn-outline" data-emie-action="click:admin-logo-remove">🗑️ 移除</button>` : ''}
            </div>
          </div>
          <div class="config-item full">
            <label>登录页背景图片</label>
            <span class="config-desc">推荐 1920x1080px，上传后自动替换登录页背景</span>
            <div class="admin-image-upload">
              ${getVal('login.bg') ? `<img src="${getVal('login.bg')}" class="admin-image-preview" id="bgPreviewImg" style="width:120px;height:68px;object-fit:cover;">` : `<div class="admin-image-preview" id="bgPreviewPlaceholder" style="display:flex;align-items:center;justify-content:center;font-size:20px;background:var(--gray-100);width:120px;height:68px;">🌄</div>`}
              <div class="admin-image-upload-btn" data-emie-action="click:admin-bg-input">
                📁 ${getVal('login.bg') ? '更换背景' : '上传背景'}
                <input type="file" id="bgUploadInput" accept="image/png,image/jpeg,image/gif,image/webp" style="display:none" data-emie-action="change:admin-bg-upload">
              </div>
              ${getVal('login.bg') ? `<button class="btn btn-sm btn-outline" data-emie-action="click:admin-bg-remove">🗑️ 移除</button>` : ''}
            </div>
          </div>
          <div class="config-item">
            <label>登录页背景色</label>
            <span class="config-desc">无背景图片时使用的颜色</span>
            <input type="text" class="config-input" data-key="login.bgColor" value="${escHtml(getVal('login.bgColor'))}" placeholder="#F3F4F6">
          </div>
        </div>
      </div>
    </div>
    <div class="config-card">
      <div class="config-card-header">
        <h3>🔒 安全设置</h3>
      </div>
      <div class="config-card-body">
        <p style="font-size:13px;color:var(--gray-500);">安全设置在「系统配置」页面中管理</p>
      </div>
    </div>`;
}

// 上传管理图片
async function uploadAdminImage(input, type) {
  if (!input.files || !input.files[0]) return;
  const file = input.files[0];
  const validTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/svg+xml', 'image/webp'];
  if (!validTypes.includes(file.type)) {
    showAdminToast('❌ 仅支持 PNG/JPG/GIF/SVG/WebP 格式', 'error');
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    showAdminToast('❌ 图片大小不能超过 5MB', 'error');
    return;
  }

  const fd = new FormData();
  fd.append('file', file);
  fd.append('type', type);
  const token = localStorage.getItem('design_pm_token');

  try {
    const r = await fetch('/api/admin/upload-image', {
      method: 'POST',
      headers: token ? { 'X-Auth-Token': token } : {},
      body: fd,
    });
    const result = await r.json();
    if (!r.ok) throw new Error(result.error || '上传失败');

    showAdminToast('✅ 上传成功，刷新页面即可看到效果', 'success');
    // 刷新外观页面
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ ' + e.message, 'error');
  }
}

// 移除管理图片
async function removeAdminImage(configKey, imgId) {
  if (!await EMIE.actions.showSystemConfirm('确定移除该图片？')) return;
  try {
    await apiPut('/admin/configs', { configs: { [configKey]: '' } });
    showAdminToast('✅ 已移除', 'success');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ ' + e.message, 'error');
  }
}

// 保存外观配置
async function saveAppearanceConfig() {
  const inputs = document.querySelectorAll('.config-input[data-key^="app."], .config-input[data-key^="login."]');
  const configs = {};
  inputs.forEach(inp => {
    configs[inp.dataset.key] = inp.value;
  });

  try {
    await apiPut('/admin/configs', { configs });
    showAdminToast('✅ 外观设置已保存，刷新页面生效', 'success');
  } catch (e) {
    showAdminToast('❌ 保存失败: ' + e.message, 'error');
  }
}


EMIE.registerActions({
  renderAdmin,
  switchAdminTab,
  renderAdminContent,
  renderAdminDashboard,
  runDataIntegrityScan,
  groupLabel,
  renderAdminConfig,
  renderFeishuV2,
  renderFeishuFieldMappings,
  saveFeishuFieldMappings,
  saveConfigGroup,
  sendNotificationTest,
  sendTemporaryBroadcast,
  loadNotificationFailures,
  retryNotificationDelivery,
  renderAdminNotificationTemplates,
  insertNotificationVariable,
  saveNotificationTemplates,
  renderAdminAppearance,
  uploadAdminImage,
  removeAdminImage,
  saveAppearanceConfig,
});

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('admin-tab', (_event, el) => switchAdminTab(el.dataset.tab));
  registerEventAction('admin-feishu-sync', (_event, el) => triggerFeishuSync(el));
  registerEventAction('admin-integrity-scan', (_event, el) => runDataIntegrityScan(el));
  registerEventAction('admin-test-notification', () => sendNotificationTest());
  registerEventAction('admin-save-config', (_event, el) => saveConfigGroup(el.dataset.configGroup));
  registerEventAction('admin-save-feishu-fields', () => saveFeishuFieldMappings());
  registerEventAction('admin-feishu-v2-refresh', () => renderAdminContent());
  registerEventAction('admin-feishu-v2-prepare', (_event, el) => prepareFeishuV2(el));
  registerEventAction('admin-feishu-v2-activate', (_event, el) => activateFeishuV2(el));
  registerEventAction('admin-feishu-v2-rollback', (_event, el) => rollbackFeishuV2(el));
  registerEventAction('admin-diagnose-feishu-fields', () => diagnoseFeishuFields());
  registerEventAction('admin-retry-sync', (_event, el) => retrySyncQueue(Number(el.dataset.queueId)));
  registerEventAction('admin-temp-broadcast', () => sendTemporaryBroadcast());
  registerEventAction('admin-notification-refresh', () => loadNotificationFailures());
  registerEventAction('admin-retry-notification', (_event, el) => retryNotificationDelivery(Number(el.dataset.deliveryId)));
  registerEventAction('admin-notification-page', (_event, el) => loadNotificationFailures(Number(el.dataset.page)));
  registerEventAction('admin-insert-variable', (_event, el) => insertNotificationVariable(el.dataset.event, el.dataset.field, el.dataset.token));
  registerEventAction('admin-save-templates', () => saveNotificationTemplates());
  registerEventAction('admin-save-appearance', () => saveAppearanceConfig());
  registerEventAction('admin-logo-input', () => document.getElementById('logoUploadInput')?.click());
  registerEventAction('admin-logo-upload', (_event, el) => uploadAdminImage(el, 'logo'));
  registerEventAction('admin-logo-remove', () => removeAdminImage('app.logo', 'logoPreviewImg'));
  registerEventAction('admin-bg-input', () => document.getElementById('bgUploadInput')?.click());
  registerEventAction('admin-bg-upload', (_event, el) => uploadAdminImage(el, 'login-bg'));
  registerEventAction('admin-bg-remove', () => removeAdminImage('login.bg', 'bgPreviewImg'));
}

EMIE.registerModule('adminShell', {
  renderAdmin,
  switchAdminTab,
  renderAdminContent,
  renderAdminDashboard,
  runDataIntegrityScan,
  renderAdminConfig,
  renderFeishuFieldMappings,
  saveFeishuFieldMappings,
  saveConfigGroup,
  sendNotificationTest,
  loadNotificationFailures,
  retryNotificationDelivery,
  renderAdminNotificationTemplates,
  insertNotificationVariable,
  saveNotificationTemplates,
  renderAdminAppearance,
  uploadAdminImage,
  removeAdminImage,
  saveAppearanceConfig,
});
