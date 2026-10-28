'use strict';
document.querySelectorAll('[data-tag]').forEach(button => button.addEventListener('click', () => {
    const input = document.getElementById('tagInput');
    const tags = input.value.split(';').map(t => t.trim()).filter(Boolean);
    if (!tags.some(t => t.toLocaleLowerCase('de') === button.dataset.tag.toLocaleLowerCase('de'))) tags.push(button.dataset.tag);
    input.value = tags.join('; ');
}));
const form = document.getElementById('editForm');
if (form) form.addEventListener('submit', event => {
    const changed = ['date', 'sender', 'main', 'filename', 'subcategory'].some(k => form.elements[k].value.trim() !== form.dataset[k]);
    if (changed && !window.confirm('Achtung, du veränderst etwas, was sich aufs Dateisystem auswirkt. Die Originaldatei wird verschoben bzw. umbenannt. Okay?')) {
        event.preventDefault();
        return;
    }
    form.elements.confirmed.value = changed ? 'true' : 'false';
});
const zone = document.getElementById('dropzone'), files = document.getElementById('files');
if (zone && files) {
    const show = () => document.getElementById('fileNames').textContent = Array.from(files.files).map(f => f.name).join(' · ') || 'Keine Dateien ausgewählt';
    files.addEventListener('change', show);
    ['dragover', 'dragenter'].forEach(name => zone.addEventListener(name, event => {
        event.preventDefault();
        zone.classList.add('dragging');
    }));
    zone.addEventListener('dragleave', () => zone.classList.remove('dragging'));
    zone.addEventListener('drop', event => {
        event.preventDefault();
        zone.classList.remove('dragging');
        files.files = event.dataTransfer.files;
        show();
    });
}

const undated = document.getElementById('undated'), period = document.getElementById('period');
if (undated && period) {
    const togglePeriod = () => {
        period.disabled = undated.checked;
    };
    undated.addEventListener('change', togglePeriod);
    togglePeriod();
}

const mainCategory=document.getElementById('mainCategory'), subcategory=document.getElementById('subcategory');
const subcategoryList=document.getElementById('subcategoryList'), subcategoryChoices=document.getElementById('subcategoryChoices');
if(mainCategory && subcategory && subcategoryList && subcategoryChoices) {
    const categoryKey=value=>value.trim().normalize('NFC').toLowerCase();
    let previousMain=categoryKey(mainCategory.value);
    const refreshSubcategories=()=>{
        const scope=categoryKey(mainCategory.value), seen=new Set();
        subcategoryList.replaceChildren();
        for(const option of subcategoryChoices.content.querySelectorAll('option')) {
            const valueKey=categoryKey(option.value);
            if((!scope || categoryKey(option.dataset.main)===scope) && !seen.has(valueKey)) {
                subcategoryList.append(option.cloneNode(true));
                seen.add(valueKey);
            }
        }
    };
    mainCategory.addEventListener('input',refreshSubcategories);
    mainCategory.addEventListener('change',()=>{
        const current=categoryKey(mainCategory.value);
        if(current!==previousMain) subcategory.value='';
        previousMain=current;
        refreshSubcategories();
    });
    refreshSubcategories();
}
