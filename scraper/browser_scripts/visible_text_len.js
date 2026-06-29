// @ts-check
/** @returns {number} */
() => (document.body ? (document.body.innerText || '') : '').replace(/\s+/g, ' ').trim().length
