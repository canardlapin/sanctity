import { h } from './elements.js';

const labels = {
  assessed: ['Assessed', 'Content checks ran.'],
  described: ['Described', 'Names and metadata were read. Recording contents were left unopened.'],
  preserved: ['Preserved', 'The original bytes and path were retained.'],
};

export function coverageChip(level) {
  if (!Object.hasOwn(labels, level)) return null;
  const [label, description] = labels[level];
  return h('span', { class: 'chip' + (level === 'assessed' ? ' ok' : ''), title: description }, label);
}

const LEVELS = ['assessed', 'described', 'preserved'];
export function coverageSection(levels) {
  const groups = new Map();
  levels.summary.forEach(s => { const g = groups.get(s.group) || { total: 0, by: {} }; g.by[s.level] = s.files; g.total += s.files; groups.set(s.group, g); });
  return h('section', { class: 'stack s8' }, h('h3', { class: 'overline' }, 'Coverage by data type'),
    h('ul', { class: 'type-list' }, [...groups].sort((x, y) => y[1].total - x[1].total).map(([g, v]) => h('li', {}, h('span', { class: 'mono' }, g),
      LEVELS.filter(l => v.by[l]).map(l => h('span', { class: l === 'assessed' ? 'ok' : 'muted' }, v.by[l] + ' ' + l))))),
    h('p', { class: 'small muted' }, 'Assessed: content checks ran. Described: names and metadata were read; recordings were not opened. Preserved: stored exactly, without interpretation.'));
}
