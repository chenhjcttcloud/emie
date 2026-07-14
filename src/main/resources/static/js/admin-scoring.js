const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);

// ==================== 评分权重管理 ====================
async function renderAdminScoring(container) {
  container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</div>`;
  try {
    const data = await apiGet('/admin/scoring-weights');
    const types = data.types || [];
    container.innerHTML = `
      <div style="max-width:760px;margin:0 auto;">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
          <div>
            <h2 style="font-size:18px;margin:0 0 4px;">⭐ 评分权重管理</h2>
            <p style="font-size:13px;color:var(--gray-400);margin:0;">按项目类型分别设置各角色评分权重（百分比）</p>
          </div>
          <button class="btn btn-outline btn-sm" data-emie-onclick="resetScoringWeights()" style="color:var(--gray-500);">↺ 重置默认</button>
        </div>
        ${types.map((t, ti) => `
          <div style="background:#fff;border:1px solid var(--gray-200);border-radius:10px;padding:20px;margin-bottom:16px;">
            <h3 style="font-size:14px;font-weight:500;margin:0 0 16px;">${t.label}</h3>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">
              ${t.weights.map(w => `
                <div style="background:var(--bg-secondary,#F9FAFB);border-radius:8px;padding:12px;">
                  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                    <span style="font-size:13px;font-weight:500;">${w.label}</span>
                    <span style="font-size:20px;font-weight:600;" id="sd_${t.type}_${w.role}">${Math.round(w.weight)}</span>
                    <span style="font-size:12px;color:var(--gray-400);">%</span>
                  </div>
                  <div style="display:flex;align-items:center;gap:8px;">
                    <input type="range" min="0" max="100" step="1" value="${Math.round(w.weight)}" style="flex:1;" data-emie-oninput="updateScoringPct('${t.type}','${w.role}',this.value)">
                    <input type="number" class="form-input" value="${Math.round(w.weight)}" min="0" max="100" step="1" style="width:55px;text-align:center;padding:4px 6px;" data-emie-onchange="updateScoringPct('${t.type}','${w.role}',this.value)">
                  </div>
                </div>
              `).join('')}
            </div>
            <div style="margin-top:12px;display:flex;justify-content:space-between;align-items:center;font-size:12px;">
              <span style="color:var(--gray-400);">合计：<strong style="font-size:16px;" id="sum_${t.type}">${t.weights.reduce((s,w) => s + Math.round(w.weight), 0)}</strong>%</span>
            </div>
          </div>
        `).join('')}
        <div style="text-align:right;">
          <button class="btn btn-primary" data-emie-onclick="saveScoringWeights()">💾 保存权重</button>
        </div>
        <div style="margin-top:16px;padding:12px;background:var(--warning-light,#FFFBEB);border-radius:8px;font-size:12px;color:#92400E;">
          💡 权重为百分比（0~100%），建议各项目类型的权重合计为 100%。综合分 = Σ(角色评分 × 权重%) ÷ Σ(权重%)。修改仅影响新建评分，历史评分不受影响。
        </div>
      </div>`;
    EMIE.adminState.scoringWeights = {};
    types.forEach(t => {
      EMIE.adminState.scoringWeights[t.type] = {};
      t.weights.forEach(w => { EMIE.adminState.scoringWeights[t.type][w.role] = Math.round(w.weight); });
    });
  } catch (e) {
    container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--danger);">加载失败: ${e.message}</div>`;
  }
}

function roleColor(role) {
  const colors = { planner: '#534AB7', sales: '#378ADD', designer: '#639922', admin: '#D85A30' };
  return colors[role] || '#888780';
}

const updateScoringPct = function(type, role, val) {
  const num = Math.round(parseFloat(val || 0));
  const clamped = Math.max(0, Math.min(100, num));
  if (!EMIE.adminState.scoringWeights) EMIE.adminState.scoringWeights = {};
  if (!EMIE.adminState.scoringWeights[type]) EMIE.adminState.scoringWeights[type] = {};
  EMIE.adminState.scoringWeights[type][role] = clamped;
  const display = document.getElementById('sd_' + type + '_' + role);
  if (display) display.textContent = clamped;
  const weights = EMIE.adminState.scoringWeights[type] || {};
  const sum = Object.values(weights).reduce((a, b) => a + b, 0);
  const sumEl = document.getElementById('sum_' + type);
  if (sumEl) {
    sumEl.textContent = sum;
    sumEl.style.color = sum === 100 ? 'var(--success,#059669)' : sum > 100 ? 'var(--danger)' : 'var(--warning,#D97706)';
  }
};

const saveScoringWeights = async function() {
  if (!EMIE.adminState.scoringWeights) return;
  try {
    await apiPut('/admin/scoring-weights', EMIE.adminState.scoringWeights);
    alert('评分权重已保存');
  } catch (e) {
    alert('保存失败: ' + e.message);
  }
};

const resetScoringWeights = async function() {
  if (!confirm('确定重置所有权重为默认值？')) return;
  window.location.reload();
};


EMIE.registerActions({
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
});

EMIE.registerModule('adminScoring', {
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
});
