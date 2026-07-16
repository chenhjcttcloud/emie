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
const renderAdminFileStorage = (...args) => EMIE.actions.renderAdminFileStorage(...args);
const renderAdminUsers = (...args) => EMIE.actions.renderAdminUsers(...args);
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);
const renderAdminWorkload = (...args) => EMIE.actions.renderAdminWorkload(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// EMIE 系统管理：配置、用户、角色、业务选项、组织、日志、归档与工作量
// ==================== 后台管理 ====================

async function renderAdmin(main, role, uid) {
  main.innerHTML = `
    <div class="admin-page">
      <div class="admin-header">
        <h2>⚙️ 系统管理</h2>
        <p>管理系统配置、外观、用户权限等</p>
      </div>
      <div class="admin-tabs" id="adminTabs">
        <button class="admin-tab ${EMIE.adminState.currentTab === 'dashboard' ? 'active' : ''}" data-emie-onclick="switchAdminTab('dashboard')">📊 概览</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'config' ? 'active' : ''}" data-emie-onclick="switchAdminTab('config')">🔧 系统配置</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'appearance' ? 'active' : ''}" data-emie-onclick="switchAdminTab('appearance')">🎨 外观</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'users' ? 'active' : ''}" data-emie-onclick="switchAdminTab('users')">👥 用户管理</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'roles' ? 'active' : ''}" data-emie-onclick="switchAdminTab('roles')">🔐 角色管理</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'categories' ? 'active' : ''}" data-emie-onclick="switchAdminTab('categories')">📂 产品类目</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'ipOptions' ? 'active' : ''}" data-emie-onclick="switchAdminTab('ipOptions')">🏷️ IP配置</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'compliance' ? 'active' : ''}" data-emie-onclick="switchAdminTab('compliance')">⚖️ 合规处罚</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'priceRanges' ? 'active' : ''}" data-emie-onclick="switchAdminTab('priceRanges')">💰 参考零售价</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'org' ? 'active' : ''}" data-emie-onclick="switchAdminTab('org')">🏢 组织架构</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'scoring' ? 'active' : ''}" data-emie-onclick="switchAdminTab('scoring')">⭐ 评分管理</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'logs' ? 'active' : ''}" data-emie-onclick="switchAdminTab('logs')">📜 日志</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'shares' ? 'active' : ''}" data-emie-onclick="switchAdminTab('shares')">🔗 分享管理</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'filestorage' ? 'active' : ''}" data-emie-onclick="switchAdminTab('filestorage')">📦 文件存储</button>
        <button class="admin-tab ${EMIE.adminState.currentTab === 'workload' ? 'active' : ''}" data-emie-onclick="switchAdminTab('workload')">📊 工作量</button>
      </div>
      <div id="adminContent"></div>
    </div>`;
  await renderAdminContent();
}

async function switchAdminTab(tab) {
  EMIE.adminState.currentTab = tab;
  localStorage.setItem('design_pm_lastAdminTab', tab);
  const tabs = document.querySelectorAll('.admin-tab');
  tabs.forEach(t => t.classList.remove('active'));
  const activeTab = document.querySelector(`.admin-tab[onclick*="${tab}"]`);
  if (activeTab) activeTab.classList.add('active');
  await renderAdminContent();
}

async function renderAdminContent() {
  const container = document.getElementById('adminContent');
  if (!container) return;
  try {
    if (EMIE.adminState.currentTab === 'dashboard') {
      await renderAdminDashboard(container);
    } else if (EMIE.adminState.currentTab === 'config') {
      await renderAdminConfig(container);
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
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

// ===== Admin: 概览 =====
async function renderAdminDashboard(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  // 获取所有配置统计
  const [configs, users] = await Promise.all([
    apiGet('/admin/configs'),
    apiGet('/admin/users'),
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
    <div class="admin-stats-grid">
      ${stats.map(s => `
        <div class="admin-stat-card">
          <div class="admin-stat-icon">${s.icon}</div>
          <div class="admin-stat-value">${s.value}</div>
          <div class="admin-stat-label">${s.label}</div>
        </div>
      `).join('')}
    </div>
    <div style="background:#fff;border-radius:12px;box-shadow:var(--shadow);padding:20px;">
      <h3 style="font-size:15px;font-weight:600;margin-bottom:16px;">📋 配置分组概览</h3>
      <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;">
        ${Object.entries(configs).map(([group, items]) => `
          <div style="padding:14px;background:var(--gray-50);border-radius:8px;border:1px solid var(--gray-200);">
            <div style="font-size:13px;font-weight:600;color:var(--gray-700);">${groupLabel(group)}</div>
            <div style="font-size:24px;font-weight:700;color:var(--primary);margin-top:4px;">${items.length}</div>
            <div style="font-size:11px;color:var(--gray-400);">个配置项</div>
          </div>
        `).join('')}
      </div>
    </div>`;
}

function groupLabel(group) {
  return { appearance: '🎨 外观设置', security: '🔒 安全设置', system: '💻 系统信息', feishu: '💬 飞书 SSO 登录', nas: '🗄️ NAS 归档', feishu_base: '📊 飞书多维表格', notification: '🔔 通知中心' }[group] || group;
}

// ===== Admin: 系统配置 =====
async function renderAdminConfig(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  const configs = await apiGet('/admin/configs');

  let html = '';
  for (const [group, items] of Object.entries(configs)) {
    if (group === 'appearance') continue; // 外观有独立页面
    html += `
      <div class="config-card" data-group="${group}">
        <div class="config-card-header">
          <h3>${groupLabel(group)}</h3>
          <button class="btn btn-sm btn-primary" data-emie-onclick="saveConfigGroup('${group}')">💾 保存</button>
        </div>
        <div class="config-card-body">
          <div class="config-grid">
            ${items.map(item => `
              <div class="config-item ${item.valueType === 'text' && (item.configValue || '').length > 60 ? 'full' : ''}">
                <label>${item.configKey.split('.').pop()}</label>
                <span class="config-desc">${escHtml(item.description || '')}</span>
                ${item.valueType === 'password'
                  ? `<input type="password" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}" autocomplete="off">`
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
        <button class="btn btn-sm btn-primary" data-emie-onclick="saveAppearanceConfig()">💾 保存</button>
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
              <div class="admin-image-upload-btn" data-emie-onclick="document.getElementById('logoUploadInput').click()">
                📁 ${getVal('app.logo') ? '更换 Logo' : '上传 Logo'}
                <input type="file" id="logoUploadInput" accept="image/png,image/jpeg,image/gif,image/svg+xml,image/webp" style="display:none" data-emie-onchange="uploadAdminImage(this, 'logo')">
              </div>
              ${getVal('app.logo') ? `<button class="btn btn-sm btn-outline" data-emie-onclick="removeAdminImage('app.logo', 'logoPreviewImg')">🗑️ 移除</button>` : ''}
            </div>
          </div>
          <div class="config-item full">
            <label>登录页背景图片</label>
            <span class="config-desc">推荐 1920x1080px，上传后自动替换登录页背景</span>
            <div class="admin-image-upload">
              ${getVal('login.bg') ? `<img src="${getVal('login.bg')}" class="admin-image-preview" id="bgPreviewImg" style="width:120px;height:68px;object-fit:cover;">` : `<div class="admin-image-preview" id="bgPreviewPlaceholder" style="display:flex;align-items:center;justify-content:center;font-size:20px;background:var(--gray-100);width:120px;height:68px;">🌄</div>`}
              <div class="admin-image-upload-btn" data-emie-onclick="document.getElementById('bgUploadInput').click()">
                📁 ${getVal('login.bg') ? '更换背景' : '上传背景'}
                <input type="file" id="bgUploadInput" accept="image/png,image/jpeg,image/gif,image/webp" style="display:none" data-emie-onchange="uploadAdminImage(this, 'login-bg')">
              </div>
              ${getVal('login.bg') ? `<button class="btn btn-sm btn-outline" data-emie-onclick="removeAdminImage('login.bg', 'bgPreviewImg')">🗑️ 移除</button>` : ''}
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
  if (!confirm('确定移除该图片？')) return;
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
  groupLabel,
  renderAdminConfig,
  saveConfigGroup,
  renderAdminAppearance,
  uploadAdminImage,
  removeAdminImage,
  saveAppearanceConfig,
});

EMIE.registerModule('adminShell', {
  renderAdmin,
  switchAdminTab,
  renderAdminContent,
  renderAdminDashboard,
  renderAdminConfig,
  saveConfigGroup,
  renderAdminAppearance,
  uploadAdminImage,
  removeAdminImage,
  saveAppearanceConfig,
});
