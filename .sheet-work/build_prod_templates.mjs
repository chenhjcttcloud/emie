import fs from 'node:fs/promises';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const outDir = '/Users/jinli/Documents/emie/outputs/project-import-templates';
await fs.mkdir(outDir, { recursive: true });

const C = { navy: '#1F4E78', pale: '#EAF3F8', border: '#D7E1E8', stripe: '#F7FBFD', required: '#FFF2CC', white: '#FFFFFF', text: '#1F2937' };
const production = {
  categories: ['灯', '音响', '相机', '个护', '其他'],
  prices: ['100元以下', '150元以下', '200元以下', '250元以下', '300元以下', '350元以下', '350元以上'],
  compliance: ['蓝牙', '无线发射', '电子电气', '电池充电', '包装镭雕', '渠道上架', '儿童相关'],
  markets: ['国内', '海外', '国内、海外'],
  statuses: ['draft', 'pending_planner', 'planner_accepted', 'in_progress', 'completed', 'completed_pending_score', 'paused', 'terminated'],
  planners: [{ id: 'feishu_ou_4526d', name: '吴思颖', title: '产品企划' }, { id: 'feishu_ou_37bb1', name: '郑诗绮', title: '产品企划' }],
  sales: [
    ['feishu_ou_1a9e1', '孙瑞婧'], ['feishu_ou_1d43f', '廖泽杰'], ['feishu_ou_58c6f', '熊敏'], ['feishu_ou_f0eac', '熊海霞'],
    ['feishu_ou_14a9d', '蔡小锐'], ['feishu_ou_c8fa9', '贺保安宇'], ['feishu_ou_38459', '郑文婧'], ['feishu_ou_225ce', '钱婧婧'], ['feishu_ou_78b2e', '钱歆鹭'],
  ],
  designers: [['feishu_ou_05a69', '彭锦添'], ['feishu_ou_16763', '戴婷婷'], ['feishu_ou_b16a5', '杨烨杰'], ['feishu_ou_544f3', '萧萧'], ['feishu_ou_2b78a', '谢梓文'], ['feishu_ou_6708e', '郭彩妮'], ['feishu_ou_a2590', '陈月珍']],
  supplychain: [['feishu_ou_d5798', '刘世娟'], ['feishu_ou_1503a', '宋巧巧'], ['feishu_ou_3cba6', '曹寅会'], ['feishu_ou_111da', '郭贺琪'], ['feishu_ou_8f0fa', '陈凡艺']],
};

const channelFields = [
  ['原项目编号', '否', '历史系统编号；没有可留空', 'legacyProjectId'],
  ['产品名称', '是', '产品名称，不能为空', 'productName'],
  ['需求方（销售）姓名', '是', '从生产参考数据中的销售姓名选择', 'salesName'],
  ['产品企划姓名', '是', '从生产参考数据中的产品企划姓名选择', 'plannerName'],
  ['产品类目', '是', '从生产参考数据中的类目选择', 'productCategory'],
  ['参考零售价', '是', '从生产参考数据中的价格区间选择', 'priceRange'],
  ['目标市场', '是', '国内、海外或国内、海外', 'targetMarket'],
  ['合规处罚', '否', '多个值用“、”分隔，例如：蓝牙、无线发射、电池充电', 'complianceItems'],
  ['要求完成时间', '是', '格式：yyyy-mm-dd', 'deadline'],
  ['产品要求', '是', '产品基本要求和目标', 'productRequirements'],
  ['细节描述', '否', '补充说明', 'description'],
  ['项目状态', '否', '历史状态编码；留空按导入规则处理', 'status'],
  ['创建时间', '否', '格式：yyyy-mm-dd', 'createdAt'],
  ['导入备注', '否', '导入人员备注，系统不写入项目业务字段', 'note'],
];
const regularFields = channelFields.filter(f => f[0] !== '需求方（销售）姓名');

function title(sheet, end, name, note) {
  sheet.mergeCells(`A1:${end}1`); sheet.getRange('A1').values = [[name]];
  sheet.getRange(`A1:${end}1`).format = { fill: C.navy, font: { bold: true, color: C.white, size: 16 }, horizontalAlignment: 'center', verticalAlignment: 'center' }; sheet.getRange(`A1:${end}1`).format.rowHeight = 30;
  sheet.mergeCells(`A2:${end}2`); sheet.getRange('A2').values = [[note]];
  sheet.getRange(`A2:${end}2`).format = { fill: C.pale, font: { color: C.text, italic: true, size: 10 }, wrapText: true, verticalAlignment: 'center' }; sheet.getRange(`A2:${end}2`).format.rowHeight = 34;
}
function header(range) { range.format = { fill: C.navy, font: { bold: true, color: C.white, size: 10 }, horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true, borders: { preset: 'all', style: 'thin', color: C.border } }; range.format.rowHeight = 36; }
function refSheet(wb) {
  const s = wb.worksheets.add('生产参考数据'); s.showGridLines = false;
  title(s, 'F', '生产环境参考数据（只读参考）', '以下内容来自当前生产数据库，仅用于选择和核对，不要修改。导入时请使用生产系统中的姓名、类目、价格区间和状态编码。');
  s.getRange('A4:D4').values = [['角色', '用户ID', '姓名', '职级']]; header(s.getRange('A4:D4'));
  const rows = [];
  production.sales.forEach(x => rows.push(['销售', x[0], x[1], ''])); production.planners.forEach(x => rows.push(['产品企划', x.id, x.name, x.title])); production.designers.forEach(x => rows.push(['设计师', x[0], x[1], '设计师'])); production.supplychain.forEach(x => rows.push(['供应链', x[0], x[1], '供应链']));
  s.getRange(`A5:D${4 + rows.length}`).values = rows;
  s.getRange(`A5:D${4 + rows.length}`).format = { borders: { preset: 'all', style: 'thin', color: C.border }, font: { color: C.text, size: 10 } };
  s.getRange('F4:I4').values = [['产品类目', '参考零售价', '合规项', '项目状态']]; header(s.getRange('F4:I4'));
  const n = Math.max(production.categories.length, production.prices.length, production.compliance.length, production.statuses.length);
  const refRows = Array.from({ length: n }, (_, i) => [production.categories[i] || '', production.prices[i] || '', production.compliance[i] || '', production.statuses[i] || '']);
  s.getRange(`F5:I${4 + n}`).values = refRows; s.getRange(`F5:I${4 + n}`).format = { borders: { preset: 'all', style: 'thin', color: C.border }, font: { color: C.text, size: 10 } };
  [14, 22, 16, 14, 4, 18, 18, 18, 24].forEach((w, i) => s.getCell(0, i).format.columnWidth = w);
  s.freezePanes.freezeRows(4); return s;
}
function build(type, fields, fileName) {
  const wb = Workbook.create(); const input = wb.worksheets.add('数据填写'); const guide = wb.worksheets.add('填写说明');
  input.showGridLines = false; guide.showGridLines = false; refSheet(wb);
  const end = String.fromCharCode(64 + fields.length); const headers = fields.map(f => f[0]);
  title(input, end, `${type}历史项目导入模板（生产环境版）`, '本模板按当前生产库结构制作。第5行开始填写真实数据，不要修改表头；黄色表头为必填字段；下拉选项来自生产环境。');
  input.getRange(`A4:${end}4`).values = [headers]; header(input.getRange(`A4:${end}4`));
  input.getRange(`A5:${end}55`).format = { borders: { preset: 'all', style: 'thin', color: '#E5E7EB' }, verticalAlignment: 'top', wrapText: true, font: { color: C.text, size: 10 } }; input.getRange(`A5:${end}55`).format.rowHeight = 24;
  fields.forEach((f, i) => { if (f[1] === '是') input.getCell(3, i).format = { fill: C.required, font: { bold: true, color: C.navy, size: 10 }, horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true }; });
  const idx = name => headers.indexOf(name); const rows = 51;
  if (idx('目标市场') >= 0) input.getRangeByIndexes(4, idx('目标市场'), rows, 1).dataValidation = { rule: { type: 'list', values: production.markets } };
  if (idx('项目状态') >= 0) input.getRangeByIndexes(4, idx('项目状态'), rows, 1).dataValidation = { rule: { type: 'list', values: production.statuses } };
  if (idx('产品类目') >= 0) input.getRangeByIndexes(4, idx('产品类目'), rows, 1).dataValidation = { rule: { type: 'list', values: production.categories } };
  if (idx('参考零售价') >= 0) input.getRangeByIndexes(4, idx('参考零售价'), rows, 1).dataValidation = { rule: { type: 'list', values: production.prices } };
  if (idx('需求方（销售）姓名') >= 0) input.getRangeByIndexes(4, idx('需求方（销售）姓名'), rows, 1).dataValidation = { rule: { type: 'list', values: production.sales.map(x => x[1]) } };
  if (idx('产品企划姓名') >= 0) input.getRangeByIndexes(4, idx('产品企划姓名'), rows, 1).dataValidation = { rule: { type: 'list', values: production.planners.map(x => x.name) } };
  ['要求完成时间', '创建时间'].forEach(n => { if (idx(n) >= 0) { const r = input.getRangeByIndexes(4, idx(n), rows, 1); r.format.numberFormat = 'yyyy-mm-dd'; r.dataValidation = { rule: { type: 'date', operator: 'between', formula1: '2000-01-01', formula2: '2099-12-31' } }; } });
  input.freezePanes.freezeRows(4); input.tables.add(`A4:${end}55`, true, `${type.replace(/[^\u4e00-\u9fa5A-Za-z]/g, '')}ProductionImportTable`);
  const widths = [16, 20, 18, 18, 14, 16, 16, 22, 16, 30, 24, 20, 16, 24]; headers.forEach((_, i) => input.getCell(0, i).format.columnWidth = widths[i] || 18);
  title(guide, 'E', `${type}模板填写说明`, '字段说明与生产参考值。实际导入前会再次校验人员、类目、价格区间和状态。');
  guide.getRange('A4:E4').values = [['字段名称', '是否必填', '填写说明', '导入字段', '生产环境要求']]; header(guide.getRange('A4:E4'));
  const guideRows = fields.map(f => [f[0], f[1], f[2], f[3], f[0] === '需求方（销售）姓名' ? '必须是生产销售姓名' : f[0] === '产品企划姓名' ? '必须是生产产品企划姓名' : f[0] === '产品类目' ? production.categories.join('、') : f[0] === '参考零售价' ? production.prices.join('、') : f[0] === '项目状态' ? production.statuses.join('、') : '按字段说明填写']);
  guide.getRange(`A5:E${4 + guideRows.length}`).values = guideRows; guide.getRange(`A5:E${4 + guideRows.length}`).format = { borders: { preset: 'all', style: 'thin', color: C.border }, wrapText: true, verticalAlignment: 'top', font: { color: C.text, size: 10 } }; guide.getRange(`A5:E${4 + guideRows.length}`).format.rowHeight = 42;
  fields.forEach((f, i) => { if (f[1] === '是') guide.getCell(4 + i, 1).format.fill = C.required; });
  guide.mergeCells(`A${7 + fields.length}:E${7 + fields.length}`); guide.getRange(`A${7 + fields.length}`).values = [['导入前检查：删除空白之外的测试行；产品名称、人员、类目、价格、市场、完成时间、产品要求必须完整；附件和参考图片暂不通过此模板导入。']]; guide.getRange(`A${7 + fields.length}:E${7 + fields.length}`).format = { fill: C.pale, wrapText: true, font: { color: C.text, size: 10 } }; guide.getRange(`A${7 + fields.length}:E${7 + fields.length}`).format.rowHeight = 42;
  [22, 12, 40, 24, 52].forEach((w, i) => guide.getCell(0, i).format.columnWidth = w); guide.freezePanes.freezeRows(4); guide.tables.add(`A4:E${4 + guideRows.length}`, true, `${type.replace(/[^\u4e00-\u9fa5A-Za-z]/g, '')}ProductionGuideTable`);
  return wb;
}
const books = [[build('渠道定制单', channelFields, '渠道定制单生产环境导入模板.xlsx'), '渠道定制单生产环境导入模板.xlsx'], [build('公司常规品', regularFields, '公司常规品生产环境导入模板.xlsx'), '公司常规品生产环境导入模板.xlsx']];
for (const [wb, name] of books) { for (const sheetName of ['数据填写', '填写说明', '生产参考数据']) { const p = await wb.render({ sheetName, autoCrop: 'all', scale: 1, format: 'png' }); await fs.writeFile(`${outDir}/${name.replace('.xlsx', '')}-${sheetName}.png`, new Uint8Array(await p.arrayBuffer())); } const x = await SpreadsheetFile.exportXlsx(wb); await x.save(`${outDir}/${name}`); }
console.log('created production-based templates');
