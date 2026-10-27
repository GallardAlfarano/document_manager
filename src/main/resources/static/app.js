'use strict';
document.querySelectorAll('[data-tag]').forEach(button => button.addEventListener('click', () => {
  const input = document.getElementById('tagInput');
  const tags = input.value.split(';').map(t=>t.trim()).filter(Boolean);
  if (!tags.some(t=>t.toLocaleLowerCase('de')===button.dataset.tag.toLocaleLowerCase('de'))) tags.push(button.dataset.tag);
  input.value=tags.join('; ');
}));
const form=document.getElementById('editForm');
if(form) form.addEventListener('submit', event => {
  const changed=['date','sender','main','filename'].some(k=>form.elements[k].value.trim()!==form.dataset[k]);
  if(changed && !window.confirm('Achtung, du veränderst etwas, was sich aufs Dateisystem auswirkt. Die Originaldatei wird verschoben bzw. umbenannt. Okay?')) { event.preventDefault(); return; }
  form.elements.confirmed.value=changed?'true':'false';
});
const zone=document.getElementById('dropzone'), files=document.getElementById('files');
if(zone && files) {
  const show=()=>document.getElementById('fileNames').textContent=Array.from(files.files).map(f=>f.name).join(' · ')||'Keine Dateien ausgewählt';
  files.addEventListener('change',show);
  ['dragover','dragenter'].forEach(name=>zone.addEventListener(name,event=>{event.preventDefault();zone.classList.add('dragging');}));
  zone.addEventListener('dragleave',()=>zone.classList.remove('dragging'));
  zone.addEventListener('drop',event=>{event.preventDefault();zone.classList.remove('dragging');files.files=event.dataTransfer.files;show();});
}
