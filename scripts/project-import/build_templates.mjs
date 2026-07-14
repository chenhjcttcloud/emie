import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const outDir = path.join(projectRoot, 'outputs/project-import-templates');
await fs.mkdir(outDir, { recursive: true });

const colors = {
  navy: '#1F4E78',
  blue: '#D9EAF7',
  lightBlue: '#EAF3F8',
  orange: '#FCE4D6',
  border: '#B7C9D6',
  gray: '#F3F6F8',
  required: '#FFF2CC',
  white: '#FFFFFF',
  text: '#1F2937',
};

const commonFields = [
  ['原项目编号', '否', '如需保留旧系统编号请填写；没有可留空', 'OLD-001', 'legacyProjectId', '仅用于追溯，不作为系统新项目编号'],
  ['产品名称', '是', '产品名称，不能为空', '智能蓝牙音箱', 'productName', '两类项目均必填'],
  ['产品企划姓名', '是', '填写系统中的产品企划姓名', '张三', 'plannerName', '请使用系统中的姓名'],
  ['产品类目', '是', '填写系统已有类目名称', '电子产品', 'productCategory', '类目必须与系统配置一致'],
  ['参考零售价', '是', '填写系统已有价格区间名称', '100-199元', 'priceRange', '价格区间必须与系统配置一致'],
  ['目标市场', '是', '国内、海外，可多选时用“、”分隔', '国内、海外', 'targetMarket', '不要使用逗号分隔'],
  ['合规处罚', '否', '如有多个，用“、”分隔', '蓝牙、无线发射', 'complianceItems', '没有可留空'],
  ['要求完成时间', '是', '日期格式：yyyy-mm-dd', new Date(2026, 6, 31), 'deadline', '不能早于今天（新建项目规则）'],
  ['产品要求', '是', '产品基本要求和目标', '支持蓝牙5.3，续航不低于12小时', 'productRequirements', '建议填写清晰、完整'],
  ['细节描述', '否', '补充说明', '优先考虑便携性', 'description', '没有可留空'],
  ['项目状态', '否', '历史项目状态；留空则按导入规则处理', 'in_progress', 'status', '可填 draft/pending_planner/planner_accepted/in_progress/completed/paused/terminated'],
  ['创建时间', '否', '历史创建时间，格式：yyyy-mm-dd', new Date(2026, 5, 1), 'createdAt', '没有可留空'],
  ['备注', '否', '给导入人员的备注，不写入项目业务字段', '历史项目，需补充附件', 'note', '导入时忽略'],
];

const channelFields = [
  ['原项目编号', '否', '如需保留旧系统编号请填写；没有可留空', 'OLD-CH-001', 'legacyProjectId', '仅用于追溯'],
  ['产品名称', '是', '产品名称，不能为空', '智能蓝牙音箱', 'productName', '两类项目均必填'],
  ['需求方（销售）姓名', '是', '填写系统中的销售姓名', '李四', 'salesName', '请使用系统中的姓名'],
  ['产品企划姓名', '是', '填写系统中的产品企划姓名', '张三', 'plannerName', '请使用系统中的姓名'],
  ...commonFields.slice(3),
];

const regularFields = [
  ['原项目编号', '否', '如需保留旧系统编号请填写；没有可留空', 'OLD-REG-001', 'legacyProjectId', '仅用于追溯'],
  ['产品名称', '是', '产品名称，不能为空', '桌面收纳灯', 'productName', '两类项目均必填'],
  ['产品企划姓名', '是', '填写系统中的产品企划姓名', '张三', 'plannerName', '请使用系统中的姓名'],
  ['产品类目', '是', '填写系统已有类目名称', '家居用品', 'productCategory', '类目必须与系统配置一致'],
  ['参考零售价', '是', '填写系统已有价格区间名称', '50-99元', 'priceRange', '价格区间必须与系统配置一致'],
  ['目标市场', '是', '国内、海外，可多选时用“、”分隔', '国内', 'targetMarket', '不要使用逗号分隔'],
  ['合规处罚', '否', '如有多个，用“、”分隔', '', 'complianceItems', '没有可留空'],
  ['要求完成时间', '是', '日期格式：yyyy-mm-dd', new Date(2026, 7, 15), 'deadline', '不能早于今天（新建项目规则）'],
  ['产品要求', '是', '产品基本要求和目标', '暖白光，支持三档亮度调节', 'productRequirements', '建议填写清晰、完整'],
  ['细节描述', '否', '补充说明', '', 'description', '没有可留空'],
  ['项目状态', '否', '历史项目状态；留空则按导入规则处理', 'in_progress', 'status', '可填 draft/planner_accepted/in_progress/completed/paused/terminated'],
  ['创建时间', '否', '历史创建时间，格式：yyyy-mm-dd', new Date(2026, 5, 10), 'createdAt', '没有可留空'],
  ['备注', '否', '给导入人员的备注，不写入项目业务字段', '', 'note', '导入时忽略'],
];

function styleTitle(sheet, endCol, title, subtitle) {
  sheet.mergeCells(`A1:${endCol}1`);
  sheet.getRange('A1').values = [[title]];
  sheet.getRange(`A1:${endCol}1`).format = {
    fill: colors.navy, font: { bold: true, color: colors.white, size: 16 },
    horizontalAlignment: 'center', verticalAlignment: 'center',
  };
  sheet.getRange(`A1:${endCol}1`).format.rowHeight = 30;
  sheet.mergeCells(`A2:${endCol}2`);
  sheet.getRange('A2').values = [[subtitle]];
  sheet.getRange(`A2:${endCol}2`).format = {
    fill: colors.lightBlue, font: { color: colors.text, italic: true, size: 10 },
    wrapText: true, verticalAlignment: 'center',
  };
  sheet.getRange(`A2:${endCol}2`).format.rowHeight = 32;
}

function buildWorkbook({ typeLabel, fileName, fields, example }) {
  const wb = Workbook.create();
  const input = wb.worksheets.add('数据填写');
  const guide = wb.worksheets.add('填写说明');
  input.showGridLines = false;
  guide.showGridLines = false;

  const headers = fields.map(f => f[0]);
  const endCol = String.fromCharCode(64 + headers.length);
  styleTitle(input, endCol, `${typeLabel}项目历史信息导入模板`, '请先阅读“填写说明”工作表。第5行为示例，正式导入前请删除或替换示例行；不要修改表头名称。黄色表头为必填字段。');
  input.getRange(`A4:${endCol}4`).values = [headers];
  input.getRange(`A4:${endCol}4`).format = {
    fill: colors.navy, font: { bold: true, color: colors.white, size: 10 },
    horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true,
    borders: { preset: 'all', style: 'thin', color: colors.border },
  };
  input.getRange(`A4:${endCol}4`).format.rowHeight = 36;
  const exampleRow = fields.map(f => f[3]);
  input.getRange(`A5:${endCol}5`).values = [exampleRow];
  input.getRange(`A5:${endCol}5`).format = {
    fill: '#F8FBFD', font: { color: '#667085', italic: true, size: 10 },
    wrapText: true, verticalAlignment: 'center',
    borders: { preset: 'all', style: 'thin', color: colors.border },
  };
  // Provide 50 blank entry rows while keeping the template compact.
  input.getRange(`A6:${endCol}55`).format = {
    borders: { preset: 'inside', style: 'thin', color: '#E5E7EB' },
    verticalAlignment: 'top', wrapText: true,
  };
  input.getRange(`A5:${endCol}55`).format.rowHeight = 24;
  input.getRange(`A5:${endCol}55`).format.font = { color: colors.text, size: 10 };
  // Required columns are highlighted in the header.
  fields.forEach((f, i) => {
    if (f[1] === '是') input.getCell(3, i).format = {
      fill: colors.required,
      font: { bold: true, color: colors.navy, size: 10 },
      horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true,
    };
  });
  // Dates are true dates with an invariant display format.
  const dateCol = headers.indexOf('要求完成时间');
  if (dateCol >= 0) input.getRangeByIndexes(4, dateCol, 51, 1).format.numberFormat = 'yyyy-mm-dd';
  const createdCol = headers.indexOf('创建时间');
  if (createdCol >= 0) input.getRangeByIndexes(4, createdCol, 51, 1).format.numberFormat = 'yyyy-mm-dd';
  // Data validation for status and market.
  const statusCol = headers.indexOf('项目状态');
  if (statusCol >= 0) input.getRangeByIndexes(4, statusCol, 51, 1).dataValidation = {
    rule: { type: 'list', values: ['draft', 'pending_planner', 'planner_accepted', 'in_progress', 'completed', 'paused', 'terminated'] },
  };
  const marketCol = headers.indexOf('目标市场');
  if (marketCol >= 0) input.getRangeByIndexes(4, marketCol, 51, 1).dataValidation = {
    rule: { type: 'list', values: ['国内', '海外', '国内、海外'] },
  };
  input.freezePanes.freezeRows(4);
  input.getRange(`A4:${endCol}55`).format.borders = { preset: 'all', style: 'thin', color: '#E5E7EB' };
  const widths = [16, 18, 18, 18, 16, 14, 18, 16, 28, 26, 18, 14, 16];
  headers.forEach((h, i) => input.getCell(0, i).format.columnWidth = widths[i] || 18);
  input.tables.add(`A4:${endCol}55`, true, `${typeLabel.replace(/[^\u4e00-\u9fa5A-Za-z]/g, '')}ImportTable`);

  styleTitle(guide, 'F', `${typeLabel}项目导入填写说明`, '此模板用于一次性历史项目导入。请只在“数据填写”工作表录入数据，不要修改表头和工作表名称。');
  guide.getRange('A4:F4').values = [['字段名称', '是否必填', '填写说明', '示例值', '导入字段', '注意事项']];
  guide.getRange('A4:F4').format = {
    fill: colors.navy, font: { bold: true, color: colors.white, size: 10 },
    horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true,
    borders: { preset: 'all', style: 'thin', color: colors.border },
  };
  guide.getRange(`A5:F${4 + fields.length}`).values = fields;
  guide.getRange(`A5:F${4 + fields.length}`).format = {
    wrapText: true, verticalAlignment: 'top', font: { color: colors.text, size: 10 },
    borders: { preset: 'all', style: 'thin', color: '#E5E7EB' },
  };
  guide.getRange(`A5:F${4 + fields.length}`).format.rowHeight = 42;
  fields.forEach((f, i) => {
    if (f[0] === '要求完成时间' || f[0] === '创建时间') guide.getCell(4 + i, 3).format.numberFormat = 'yyyy-mm-dd';
  });
  fields.forEach((f, i) => { if (f[1] === '是') guide.getCell(4 + i, 1).format.fill = colors.required; });
  guide.getRange(`A${6 + fields.length}:F${7 + fields.length}`).merge(true);
  guide.getRange(`A${6 + fields.length}:F${6 + fields.length}`).values = [['导入前检查清单：']];
  guide.getRange(`A${6 + fields.length}:F${6 + fields.length}`).format = { fill: colors.blue, font: { bold: true, color: colors.navy } };
  guide.getRange(`A${7 + fields.length}:F${7 + fields.length}`).values = [['1）删除第5行示例；2）必填字段不能留空；3）姓名、类目、价格区间必须与系统配置一致；4）日期统一使用 yyyy-mm-dd；5）附件和参考图片暂不在此模板导入，后续可单独补充。']];
  guide.getRange(`A${7 + fields.length}:F${7 + fields.length}`).format = { wrapText: true, fill: colors.gray, font: { color: colors.text, size: 10 } };
  guide.getRange(`A${7 + fields.length}:F${7 + fields.length}`).format.rowHeight = 40;
  guide.freezePanes.freezeRows(4);
  [18, 12, 36, 20, 22, 34].forEach((w, i) => guide.getCell(0, i).format.columnWidth = w);
  guide.tables.add(`A4:F${4 + fields.length}`, true, `${typeLabel.replace(/[^\u4e00-\u9fa5A-Za-z]/g, '')}GuideTable`);
  return wb;
}

const channelWb = buildWorkbook({
  typeLabel: '渠道定制单',
  fileName: '渠道定制单历史项目导入模板.xlsx',
  fields: channelFields,
  example: true,
});
const regularWb = buildWorkbook({
  typeLabel: '公司常规品',
  fileName: '公司常规品历史项目导入模板.xlsx',
  fields: regularFields,
  example: true,
});

for (const [wb, name] of [[channelWb, '渠道定制单历史项目导入模板.xlsx'], [regularWb, '公司常规品历史项目导入模板.xlsx']]) {
  for (const sheetName of ['数据填写', '填写说明']) {
    const preview = await wb.render({ sheetName, autoCrop: 'all', scale: 1, format: 'png' });
    await fs.writeFile(`${outDir}/${name.replace('.xlsx', '')}-${sheetName}.png`, new Uint8Array(await preview.arrayBuffer()));
  }
  const xlsx = await SpreadsheetFile.exportXlsx(wb);
  await xlsx.save(`${outDir}/${name}`);
}
console.log('created', outDir);
