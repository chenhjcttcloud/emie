const EMIE = window.EMIE;
const switchAdminTab = (...args) => EMIE.actions.switchAdminTab(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const submitGuard = (...args) => EMIE.actions.submitGuard(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

// ==================== 管理员：产品类目管理 ====================
async function renderAdminCategories(container) {
  async function loadAndRender() {
    const cats = await apiGet('/categories/all');
    const nameList = cats.map(c => c.name).join(', ');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>📂 产品类目管理</h3>
          <button class="btn btn-primary btn-sm" data-emie-onclick="addCategory()">➕ 新增类目</button>
        </div>
        <div class="config-card-body">
          <p style="font-size:13px;color:var(--gray-500);margin-bottom:16px;">当前类目（顺序按排序号）：${nameList}</p>
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${cats.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" data-emie-onclick="editCategory(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" data-emie-onclick="deleteCategory(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

// ==================== 新增类目弹窗 ====================
const addCategory = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'categoryEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('categoryEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📂 新增产品类目</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 类目名称</label><input class="form-input" id="catName" placeholder="如：灯、音响..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="catOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('categoryEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="saveCategory(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const editCategory = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'categoryEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('categoryEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑产品类目</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 类目名称</label><input class="form-input" id="catName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="catOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="catActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('categoryEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="saveCategory(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const saveCategory = async function(id) {
  const name = document.getElementById('catName')?.value?.trim();
  if (!name) { window.EMIE.actions.showSystemAlert('请输入类目名称'); return; }
  const sortOrder = parseInt(document.getElementById('catOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('catActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/categories/${id}`, body);
  } else {
    await apiPost('/categories', body);
  }
  closeM('categoryEditModal');
  // 刷新类目列表和主界面
  try { EMIE.state.categories = await apiGet('/categories'); } catch(e) {}
  switchAdminTab('categories');
};

const deleteCategory = async function(id) {
  if (!confirm('确定删除该类目？已关联的项目不受影响。')) return;
  await apiDelete(`/categories/${id}`);
  try { EMIE.state.categories = await apiGet('/categories'); } catch(e) {}
  switchAdminTab('categories');
};

// ==================== 管理员：IP配置管理 ====================
async function renderAdminIpOptions(container) {
  const items = await apiGet('/ip-options/all');
  const nextSortOrder = items.reduce((maxOrder, item) => Math.max(maxOrder, Number(item.sortOrder) || 0), 0) + 1;
  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>🏷️ IP配置管理</h3>
        <button class="btn btn-primary btn-sm" data-emie-onclick="addIpOption(${nextSortOrder})">➕ 新增IP</button>
      </div>
      <div class="config-card-body">
        <p style="font-size:13px;color:var(--gray-500);margin-bottom:14px;">启用的IP会出现在渠道定制单和公司常规品项目的新建页面中。可为每个一级IP配置二级标签，并选择单选或多选。</p>
        ${items.length === 0 ? `<div class="empty" style="padding:30px;"><div class="empty-icon">🏷️</div><p>暂未配置IP</p></div>` : `
        <div class="table-wrap"><table>
          <thead><tr><th>ID</th><th>IP名称</th><th>二级选项</th><th>选择方式</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>${items.map(item => `
            <tr>
              <td>${item.id}</td>
              <td><strong>${escHtml(item.name)}</strong></td>
              <td>${(() => { try { return JSON.parse(item.subOptionsJson || '[]').map(escHtml).join('、') || '-'; } catch(e) { return '-'; } })()}</td>
              <td>${item.subOptionSelectionMode === 'single' ? '单选' : '多选'}</td>
              <td>${item.sortOrder}</td>
              <td><span class="badge ${item.active ? 'badge-completed' : 'badge-rejected'}">${item.active ? '启用' : '禁用'}</span></td>
              <td style="white-space:nowrap;">
                <button class="btn btn-outline btn-sm" data-emie-onclick="editIpOption(${item.id}, '${escHtml(escJsString(item.name))}', ${item.sortOrder}, ${item.active}, '${encodeURIComponent(item.subOptionsJson || '[]')}', '${item.subOptionSelectionMode || 'multiple'}')">✏️ 编辑</button>
                <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" data-emie-onclick="deleteIpOption(${item.id})">🗑️ 删除</button>
              </td>
            </tr>`).join('')}
          </tbody>
        </table></div>`}
      </div>
    </div>`;
}

const addIpOption = function(nextSortOrder = 1) {
  openIpOptionModal(null, '', nextSortOrder, true);
};

const editIpOption = function(id, name, sortOrder, active, encodedSubOptions = '%5B%5D', selectionMode = 'multiple') {
  let subOptions = [];
  try { subOptions = JSON.parse(decodeURIComponent(encodedSubOptions)); } catch (e) {}
  openIpOptionModal(id, name, sortOrder, active, subOptions, selectionMode);
};

function openIpOptionModal(id, name, sortOrder, active, subOptions = [], selectionMode = 'multiple') {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'ipOptionEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('ipOptionEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">${id ? '✏️ 编辑IP' : '🏷️ 新增IP'}</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> IP名称</label><input class="form-input" id="ipOptionName" value="${escHtml(name)}" maxlength="100" placeholder="请输入IP名称"></div>
        <div class="form-group"><label class="form-label">二级IP选项</label><textarea class="form-textarea" id="ipOptionSubOptions" maxlength="3000" placeholder="每行一个，例如：&#10;小黄人&#10;KT猫">${escHtml((subOptions || []).join('\n'))}</textarea><div class="form-hint">留空时新建项目页不会显示二级选项；支持换行或逗号分隔。</div></div>
        <div class="form-group"><label class="form-label">二级选项选择方式</label><select class="form-select" id="ipOptionSelectionMode"><option value="multiple" ${selectionMode !== 'single' ? 'selected' : ''}>多选</option><option value="single" ${selectionMode === 'single' ? 'selected' : ''}>单选</option></select></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="ipOptionOrder" type="number" value="${sortOrder || 0}" placeholder="数字越小越靠前"></div>
        ${id ? `<div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="ipOptionActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>` : ''}
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('ipOptionEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>saveIpOption(${id || 'null'}))">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  document.getElementById('ipOptionName')?.focus();
}

const saveIpOption = async function(id) {
  const name = document.getElementById('ipOptionName')?.value?.trim();
  if (!name) { window.EMIE.actions.showSystemAlert('请输入IP名称'); return; }
  const sortOrder = parseInt(document.getElementById('ipOptionOrder')?.value, 10) || 0;
  const body = { name, sortOrder: String(sortOrder), subOptions: document.getElementById('ipOptionSubOptions')?.value || '', subOptionSelectionMode: document.getElementById('ipOptionSelectionMode')?.value || 'multiple' };
  if (id) body.active = document.getElementById('ipOptionActive')?.value || 'true';
  try {
    if (id) await apiPut(`/ip-options/${id}`, body);
    else await apiPost('/ip-options', body);
    closeM('ipOptionEditModal');
    EMIE.state.ipOptions = await apiGet('/ip-options');
    await switchAdminTab('ipOptions');
  } catch (e) {
    window.EMIE.actions.showSystemAlert('保存失败：' + e.message);
  }
};

const deleteIpOption = async function(id) {
  if (!confirm('确定删除该IP配置？历史项目中已保存的IP仍会保留。')) return;
  try {
    await apiDelete(`/ip-options/${id}`);
    EMIE.state.ipOptions = await apiGet('/ip-options');
    await switchAdminTab('ipOptions');
  } catch (e) {
    window.EMIE.actions.showSystemAlert('删除失败：' + e.message);
  }
};

// ==================== 管理员：合规处罚管理 ====================
async function renderAdminCompliance(container) {
  async function loadAndRender() {
    const items = await apiGet('/compliance/all');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>⚖️ 合规处罚管理</h3>
          <button class="btn btn-primary btn-sm" data-emie-onclick="addCompliance()">➕ 新增合规项</button>
        </div>
        <div class="config-card-body">
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${items.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" data-emie-onclick="editCompliance(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" data-emie-onclick="deleteCompliance(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

const addCompliance = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'complianceEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('complianceEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">⚖️ 新增合规处罚项</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="compName" placeholder="如：蓝牙、无线发射..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="compOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('complianceEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="saveCompliance(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const editCompliance = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'complianceEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('complianceEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑合规处罚项</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="compName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="compOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="compActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('complianceEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="saveCompliance(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const saveCompliance = async function(id) {
  const name = document.getElementById('compName')?.value?.trim();
  if (!name) { window.EMIE.actions.showSystemAlert('请输入名称'); return; }
  const sortOrder = parseInt(document.getElementById('compOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('compActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/compliance/${id}`, body);
  } else {
    await apiPost('/compliance', body);
  }
  closeM('complianceEditModal');
  try { EMIE.state.complianceItems = await apiGet('/compliance'); } catch(e) {}
  switchAdminTab('compliance');
};

const deleteCompliance = async function(id) {
  if (!confirm('确定删除该合规项？')) return;
  await apiDelete(`/compliance/${id}`);
  try { EMIE.state.complianceItems = await apiGet('/compliance'); } catch(e) {}
  switchAdminTab('compliance');
};

// ==================== 管理员：参考零售价管理 ====================
async function renderAdminPriceRanges(container) {
  async function loadAndRender() {
    const items = await apiGet('/price-ranges/all');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>💰 参考零售价管理</h3>
          <button class="btn btn-primary btn-sm" data-emie-onclick="addPriceRange()">➕ 新增价格</button>
        </div>
        <div class="config-card-body">
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${items.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" data-emie-onclick="editPriceRange(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" data-emie-onclick="deletePriceRange(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

const addPriceRange = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'priceRangeEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('priceRangeEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">💰 新增参考零售价</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="prName" placeholder="如：100元以下、150元以下..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="prOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('priceRangeEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="savePriceRange(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const editPriceRange = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'priceRangeEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('priceRangeEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑参考零售价</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="prName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="prOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="prActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('priceRangeEditModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="savePriceRange(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

const savePriceRange = async function(id) {
  const name = document.getElementById('prName')?.value?.trim();
  if (!name) { window.EMIE.actions.showSystemAlert('请输入名称'); return; }
  const sortOrder = parseInt(document.getElementById('prOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('prActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/price-ranges/${id}`, body);
  } else {
    await apiPost('/price-ranges', body);
  }
  closeM('priceRangeEditModal');
  try { EMIE.state.priceRanges = await apiGet('/price-ranges'); } catch(e) {}
  switchAdminTab('priceRanges');
};

const deletePriceRange = async function(id) {
  if (!confirm('确定删除该价格？')) return;
  await apiDelete(`/price-ranges/${id}`);
  try { EMIE.state.priceRanges = await apiGet('/price-ranges'); } catch(e) {}
  switchAdminTab('priceRanges');
};

/** 刷新组织架构及用户数据 */

EMIE.registerActions({
  renderAdminCategories,
  renderAdminIpOptions,
  openIpOptionModal,
  renderAdminCompliance,
  renderAdminPriceRanges,
  addCategory,
  editCategory,
  saveCategory,
  deleteCategory,
  addIpOption,
  editIpOption,
  saveIpOption,
  deleteIpOption,
  addCompliance,
  editCompliance,
  saveCompliance,
  deleteCompliance,
  addPriceRange,
  editPriceRange,
  savePriceRange,
  deletePriceRange,
});

EMIE.registerModule('adminCatalog', {
  renderAdminCategories,
  addCategory,
  editCategory,
  saveCategory,
  deleteCategory,
  renderAdminIpOptions,
  addIpOption,
  editIpOption,
  saveIpOption,
  deleteIpOption,
  renderAdminCompliance,
  addCompliance,
  editCompliance,
  saveCompliance,
  deleteCompliance,
  renderAdminPriceRanges,
  addPriceRange,
  editPriceRange,
  savePriceRange,
  deletePriceRange,
});
