/**
 * Gym Hisab - READ-ONLY inspection script.  *** USE ONLY ON A COPY OF THE SPREADSHEET ***
 *
 * How to use (no deployment needed):
 *  1. In Google Sheets open your COPY ("Copy of ...").  Extensions > Apps Script.
 *  2. Paste this file as the only code in that copy's script project. Save.
 *  3. Pick the function  previewReport  and press Run (allow access when asked).
 *  4. Open View > Logs (Execution log) and send me the printed JSON.
 *
 * This script is bound to the sheet it is pasted into (getActiveSpreadsheet), so it cannot
 * touch your live sheet by accident. It never calls setValue/setValues/clear/delete/insert/append.
 */

function previewReport() {
  Logger.log(JSON.stringify(buildReport(), null, 2));
}

function readTab(name) {
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  if (!sh) return { sh: null, values: [], headers: [] };
  const last = sh.getLastRow(), cols = sh.getLastColumn();
  if (last < 1 || cols < 1) return { sh: sh, values: [], headers: [] };
  const all = sh.getRange(1, 1, last, cols).getDisplayValues();
  return { sh: sh, headers: all[0], values: all.slice(1) };
}

function num(v) { const n = Number(String(v).replace(/[^0-9.\-]/g, '')); return isNaN(n) ? 0 : n; }

function buildReport() {
  const M = readTab('Members'), P = readTab('Payments');
  const rep = {
    tabs: SpreadsheetApp.getActiveSpreadsheet().getSheets().map(s => s.getName()),
    membersHeaders: M.headers, paymentsHeaders: P.headers,
    memberRows: 0, paymentRows: 0,
    duplicateMemberIds: [], duplicatePaymentIds: [], blankMemberIds: [],
    orphanPayments: [], possibleDuplicatePayments: [], monthDiffersFromPaidOn: [],
    totalPaidMismatch: [], memberFormulaColumns: {}, memberRowsWithTypedValuesInFtoI: [],
    paymentModes: {}, feesByMember: []
  };

  // Members: A ID, B Name, C Phone, D Join Date, E Monthly Fee, F..I formulas/values
  const byId = {};
  M.values.forEach((r, i) => {
    const row = i + 2, id = String(r[0]).trim();
    if (!id && !String(r[1]).trim()) return;
    rep.memberRows++;
    if (!id) { rep.blankMemberIds.push(row); return; }
    (byId[id] = byId[id] || []).push({ row: row, name: r[1], joined: r[3], fee: num(r[4]), totalPaid: num(r[8]) });
  });
  Object.keys(byId).forEach(id => { if (byId[id].length > 1) rep.duplicateMemberIds.push({ id: id, rows: byId[id] }); });

  // Which of F:I are real formulas in each row?
  if (M.sh && M.values.length) {
    const f = M.sh.getRange(2, 6, M.values.length, 4).getFormulas();
    ['F', 'G', 'H', 'I'].forEach((c, k) => rep.memberFormulaColumns[c] = f.filter(r => r[k] !== '').length);
    f.forEach((r, i) => { if (M.values[i][0] && r.some(x => x === '')) rep.memberRowsWithTypedValuesInFtoI.push(i + 2); });
  }

  // Payments: A ID, B Member ID, C Name, D Month, E Amount, F Paid On, G Mode, H Note
  const seenPay = {}, perDay = {}, paidSum = {};
  P.values.forEach((r, i) => {
    const row = i + 2, pid = String(r[0]).trim(), mid = String(r[1]).trim();
    if (!pid && !mid) return;
    rep.paymentRows++;
    const amt = num(r[4]), month = String(r[3]).trim(), on = String(r[5]).trim();
    rep.paymentModes[r[6]] = (rep.paymentModes[r[6]] || 0) + 1;
    if (seenPay[pid]) rep.duplicatePaymentIds.push({ id: pid, rows: [seenPay[pid], row] }); else seenPay[pid] = row;
    if (!byId[mid]) rep.orphanPayments.push({ row: row, paymentId: pid, memberId: mid, name: r[2], amount: amt, paidOn: on });
    const key = mid + '|' + on + '|' + amt;
    (perDay[key] = perDay[key] || []).push(row);
    if (month && on && month !== on.slice(0, 7)) rep.monthDiffersFromPaidOn.push({ row: row, paymentId: pid, month: month, paidOn: on });
    paidSum[mid] = (paidSum[mid] || 0) + amt;
  });
  Object.keys(perDay).forEach(k => { if (perDay[k].length > 1) rep.possibleDuplicatePayments.push({ memberDateAmount: k, rows: perDay[k] }); });

  Object.keys(byId).forEach(id => {
    byId[id].forEach(m => {
      if (m.totalPaid !== (paidSum[id] || 0)) rep.totalPaidMismatch.push({ id: id, row: m.row, sheetTotalPaid: m.totalPaid, sumOfPayments: paidSum[id] || 0 });
    });
    rep.feesByMember.push({ id: id, fee: byId[id][0].fee, joined: byId[id][0].joined });
  });
  return rep;
}
