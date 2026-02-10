// ========================================
// Application State Management
// ========================================

const AppState = {
    files: [],
    currentConfig: {
        inversionMode: 'full',
        pageRange: 'all',
        customRange: '',
        outputDpi: 300,
        compress: false
    },
    processing: false,
    processingHidden: false,
    theme: 'dark',
    processedFiles: [],
    previewIndex: 0,
    filteredPages: []
};

// ========================================
// API Configuration
// ========================================

const API_CONFIG = {
    baseUrl: '/api',
    endpoints: {
        process: '/pdf/process',
        batch: '/pdf/batch',
        auth: '/auth/login',
        oauth: '/auth/google'
    }
};

// ========================================
// IndexedDB for Local Storage
// ========================================

class StorageManager {
    constructor() {
        this.dbName = 'PDFInverterDB';
        this.version = 1;
        this.db = null;
    }

    async init() {
        return new Promise((resolve, reject) => {
            const request = indexedDB.open(this.dbName, this.version);

            request.onerror = () => reject(request.error);
            request.onsuccess = () => {
                this.db = request.result;
                resolve();
            };

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                
                // Store for file history
                if (!db.objectStoreNames.contains('history')) {
                    const historyStore = db.createObjectStore('history', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    historyStore.createIndex('date', 'date', { unique: false });
                }

                // Store for user preferences
                if (!db.objectStoreNames.contains('preferences')) {
                    db.createObjectStore('preferences', { keyPath: 'key' });
                }
            };
        });
    }

    async saveHistory(data) {
        const transaction = this.db.transaction(['history'], 'readwrite');
        const store = transaction.objectStore('history');
        
        const historyEntry = {
            fileName: data.fileName,
            mode: data.mode,
            pageRange: data.pageRange,
            date: new Date().toISOString(),
            fileSize: data.fileSize
        };

        return new Promise((resolve, reject) => {
            const request = store.add(historyEntry);
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }

    async getHistory(limit = 50) {
        const transaction = this.db.transaction(['history'], 'readonly');
        const store = transaction.objectStore('history');
        const index = store.index('date');

        return new Promise((resolve, reject) => {
            const request = index.openCursor(null, 'prev');
            const results = [];
            
            request.onsuccess = (event) => {
                const cursor = event.target.result;
                if (cursor && results.length < limit) {
                    results.push(cursor.value);
                    cursor.continue();
                } else {
                    resolve(results);
                }
            };
            request.onerror = () => reject(request.error);
        });
    }

    async savePreference(key, value) {
        const transaction = this.db.transaction(['preferences'], 'readwrite');
        const store = transaction.objectStore('preferences');

        return new Promise((resolve, reject) => {
            const request = store.put({ key, value });
            request.onsuccess = () => resolve();
            request.onerror = () => reject(request.error);
        });
    }

    async getPreference(key) {
        const transaction = this.db.transaction(['preferences'], 'readonly');
        const store = transaction.objectStore('preferences');

        return new Promise((resolve, reject) => {
            const request = store.get(key);
            request.onsuccess = () => resolve(request.result?.value);
            request.onerror = () => reject(request.error);
        });
    }
}

const storage = new StorageManager();

// ========================================
// Preview Controller
// ========================================

class PreviewController {
    constructor() {
        this.originalPDF = null;
        this.invertedPDF = null;
        this.currentPage = 1;
        this.totalPages = 1;
        this.filteredPageNumbers = []; // 1-based page numbers to show
        
        this.elements = {
            originalCanvas: document.getElementById('originalCanvas'),
            invertedCanvas: document.getElementById('invertedCanvas'),
            prevPage: document.getElementById('prevPage'),
            nextPage: document.getElementById('nextPage'),
            pageInfo: document.getElementById('pageInfo')
        };
        
        this.bindEvents();
    }

    reset() {
        this.originalPDF = null;
        this.invertedPDF = null;
        this.currentPage = 1;
        this.totalPages = 1;
        this.filteredPageNumbers = [];
        this.elements.pageInfo.textContent = 'Page 1 of 1';
        const originalContext = this.elements.originalCanvas.getContext('2d');
        const invertedContext = this.elements.invertedCanvas.getContext('2d');
        originalContext.clearRect(0, 0, this.elements.originalCanvas.width, this.elements.originalCanvas.height);
        invertedContext.clearRect(0, 0, this.elements.invertedCanvas.width, this.elements.invertedCanvas.height);
    }
    
    bindEvents() {
        this.elements.prevPage.addEventListener('click', () => this.changePage(-1));
        this.elements.nextPage.addEventListener('click', () => this.changePage(1));
    }
    
    async loadPDFs(originalFile, invertedBlob, filteredPages) {
        try {
            const originalArrayBuffer = await originalFile.arrayBuffer();
            const invertedArrayBuffer = await invertedBlob.arrayBuffer();
            
            this.originalPDF = await pdfjsLib.getDocument({ data: originalArrayBuffer }).promise;
            this.invertedPDF = await pdfjsLib.getDocument({ data: invertedArrayBuffer }).promise;
            
            // filteredPages = 1-based page numbers that were processed
            if (filteredPages && filteredPages.length > 0) {
                this.filteredPageNumbers = filteredPages;
            } else {
                // Show all pages of the original
                this.filteredPageNumbers = [];
                for (let i = 1; i <= this.originalPDF.numPages; i++) {
                    this.filteredPageNumbers.push(i);
                }
            }
            this.totalPages = this.filteredPageNumbers.length;
            this.currentPage = 1;
            
            await this.renderCurrentPage();
        } catch (error) {
            console.error('Error loading PDFs:', error);
            throw error;
        }
    }
    
    async renderCurrentPage() {
        try {
            // Map filtered index to actual PDF page numbers
            const originalPageNum = this.filteredPageNumbers[this.currentPage - 1];
            // The inverted PDF only contains the filtered pages (sequentially numbered)
            const invertedPageNum = this.currentPage;

            const [originalPage, invertedPage] = await Promise.all([
                this.originalPDF.getPage(originalPageNum),
                this.invertedPDF.getPage(Math.min(invertedPageNum, this.invertedPDF.numPages))
            ]);
            
            const viewport = originalPage.getViewport({ scale: 1.0 });
            
            // Set canvas dimensions
            this.elements.originalCanvas.width = viewport.width;
            this.elements.originalCanvas.height = viewport.height;
            this.elements.invertedCanvas.width = viewport.width;
            this.elements.invertedCanvas.height = viewport.height;
            
            // Render both pages
            const originalContext = this.elements.originalCanvas.getContext('2d');
            const invertedContext = this.elements.invertedCanvas.getContext('2d');
            
            await Promise.all([
                originalPage.render({ canvasContext: originalContext, viewport }).promise,
                invertedPage.render({ canvasContext: invertedContext, viewport: invertedPage.getViewport({ scale: 1.0 }) }).promise
            ]);
            
            this.updatePageInfo();
        } catch (error) {
            console.error('Error rendering page:', error);
        }
    }
    
    changePage(delta) {
        const newPage = this.currentPage + delta;
        if (newPage >= 1 && newPage <= this.totalPages) {
            this.currentPage = newPage;
            this.renderCurrentPage();
        }
    }
    
    updatePageInfo() {
        const displayPage = this.filteredPageNumbers[this.currentPage - 1];
        this.elements.pageInfo.textContent = `Page ${displayPage} (${this.currentPage} of ${this.totalPages})`;
        this.elements.prevPage.disabled = this.currentPage === 1;
        this.elements.nextPage.disabled = this.currentPage === this.totalPages;
    }
}

// ========================================
// UI Controller
// ========================================

class UIController {
    constructor() {
        this.elements = {
            uploadSection: document.getElementById('uploadSection'),
            fileListSection: document.getElementById('fileListSection'),
            configSection: document.getElementById('configSection'),
            processingSection: document.getElementById('processingSection'),
            previewSection: document.getElementById('previewSection'),
            resultsSection: document.getElementById('resultsSection'),
            
            uploadZone: document.getElementById('uploadZone'),
            fileInput: document.getElementById('fileInput'),
            fileList: document.getElementById('fileList'),
            
            processBtn: document.getElementById('processBtn'),
            cancelBtn: document.getElementById('cancelBtn'),
            downloadBtn: document.getElementById('downloadBtn'),
            clearFiles: document.getElementById('clearFiles'),
            
            processingStatus: document.getElementById('processingStatus'),
            progressFill: document.getElementById('progressFill'),

            previewFileSelect: document.getElementById('previewFileSelect'),
            previewFileName: document.getElementById('previewFileName'),
            prevFile: document.getElementById('prevFile'),
            nextFile: document.getElementById('nextFile'),
            
            themeToggle: document.getElementById('themeToggle'),

            logoLink: document.getElementById('logoLink'),
            pageRangeCard: document.getElementById('pageRangeCard'),
            resultsFileList: document.getElementById('resultsFileList')
        };

        this.previewController = new PreviewController();
        this.bindEvents();
        this.initTheme();
    }

    bindEvents() {
        // Logo → home
        this.elements.logoLink?.addEventListener('click', (e) => {
            e.preventDefault();
            this.resetApp(false);
        });

        // Upload events — also acts as "Upload New" when on results page
        this.elements.uploadZone.addEventListener('click', () => {
            // If on results page, reset first
            if (AppState.processedFiles.length > 0) {
                this.resetApp(true);
                return;
            }
            this.elements.fileInput.click();
        });

        this.elements.fileInput.addEventListener('change', (e) => {
            this.handleFileSelect(e.target.files);
        });

        // Config events
        document.querySelectorAll('input[name="inversionMode"]').forEach(radio => {
            radio.addEventListener('change', (e) => {
                AppState.currentConfig.inversionMode = e.target.value;
            });
        });

        document.querySelectorAll('input[name="pageRange"]').forEach(radio => {
            radio.addEventListener('change', (e) => {
                AppState.currentConfig.pageRange = e.target.value;
                const customInput = document.getElementById('customRangeInput');
                customInput.style.display = e.target.value === 'custom' ? 'flex' : 'none';
            });
        });

        document.getElementById('customRange').addEventListener('input', (e) => {
            AppState.currentConfig.customRange = e.target.value;
        });

        // Output settings
        document.getElementById('outputDpi')?.addEventListener('change', (e) => {
            AppState.currentConfig.outputDpi = Number(e.target.value);
        });

        document.getElementById('compressOutput')?.addEventListener('change', (e) => {
            AppState.currentConfig.compress = e.target.checked;
        });

        // Preset buttons
        document.querySelectorAll('.preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const mode = btn.dataset.mode;
                const range = btn.dataset.range;
                
                document.querySelector(`input[name="inversionMode"][value="${mode}"]`).checked = true;
                document.querySelector(`input[name="pageRange"][value="${range}"]`).checked = true;
                
                AppState.currentConfig.inversionMode = mode;
                AppState.currentConfig.pageRange = range;
                const customInput = document.getElementById('customRangeInput');
                customInput.style.display = range === 'custom' ? 'flex' : 'none';
            });
        });

        this.elements.previewFileSelect?.addEventListener('change', (e) => {
            const index = Number(e.target.value);
            if (!Number.isNaN(index)) {
                AppState.previewIndex = index;
                this.loadPreviewByIndex(index);
            }
        });

        this.elements.prevFile?.addEventListener('click', () => {
            this.shiftPreviewFile(-1);
        });

        this.elements.nextFile?.addEventListener('click', () => {
            this.shiftPreviewFile(1);
        });

        // Button events
        this.elements.processBtn?.addEventListener('click', () => {
            this.processFiles();
        });

        this.elements.clearFiles?.addEventListener('click', () => {
            this.clearAllFiles();
        });

        this.elements.cancelBtn?.addEventListener('click', () => {
            this.cancelProcessing();
        });

        this.elements.downloadBtn?.addEventListener('click', () => {
            this.downloadResults();
        });

        // Theme toggle
        this.elements.themeToggle?.addEventListener('click', () => {
            this.toggleTheme();
        });
    }

    handleFileSelect(files) {
        const pdfFiles = Array.from(files).filter(file => file.type === 'application/pdf');
        
        if (pdfFiles.length === 0) {
            alert('Please select PDF files only.');
            return;
        }

        // Check file size (50MB limit)
        const oversizedFiles = pdfFiles.filter(file => file.size > 50 * 1024 * 1024);
        if (oversizedFiles.length > 0) {
            alert(`The following files exceed 50MB limit: ${oversizedFiles.map(f => f.name).join(', ')}`);
            return;
        }

        pdfFiles.forEach(file => {
            AppState.files.push({
                file: file,
                name: file.name,
                size: this.formatFileSize(file.size),
                pageCount: null // Will be populated after loading
            });
        });

        this.updateFileList();
        this.showSection('fileListSection');
        this.showSection('configSection');
        this.updatePageRangeVisibility();
    }

    async updateFileList() {
        this.elements.fileList.innerHTML = '';

        for (const fileData of AppState.files) {
            // Get page count if not already loaded
            if (fileData.pageCount === null) {
                fileData.pageCount = await this.getPageCount(fileData.file);
            }

            const fileItem = document.createElement('div');
            fileItem.className = 'file-item';
            fileItem.innerHTML = `
                <div class="file-info">
                    <div class="file-icon">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M7 2h7l5 5v11a2 2 0 01-2 2H7a2 2 0 01-2-2V4a2 2 0 012-2z"/>
                            <path d="M14 2v5h5" stroke="white" stroke-width="2" fill="none"/>
                        </svg>
                    </div>
                    <div class="file-details">
                        <div class="file-name">${fileData.name}</div>
                        <div class="file-meta">
                            <span>${fileData.size}</span>
                            <span>${fileData.pageCount} pages</span>
                        </div>
                    </div>
                </div>
                <div class="file-actions">
                    <button class="file-remove" data-file="${fileData.name}">
                        <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                            <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd"/>
                        </svg>
                    </button>
                </div>
            `;

            fileItem.querySelector('.file-remove').addEventListener('click', () => {
                this.removeFile(fileData.name);
            });

            this.elements.fileList.appendChild(fileItem);
        }
    }

    async getPageCount(file) {
        try {
            const arrayBuffer = await file.arrayBuffer();
            const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
            return pdf.numPages;
        } catch (error) {
            console.error('Error loading PDF:', error);
            return 0;
        }
    }

    removeFile(fileName) {
        AppState.files = AppState.files.filter(f => f.name !== fileName);
        
        if (AppState.files.length === 0) {
            this.hideSection('fileListSection');
            this.hideSection('configSection');
        } else {
            this.updateFileList();
            this.updatePageRangeVisibility();
        }
    }

    clearAllFiles() {
        AppState.files = [];
        this.hideSection('fileListSection');
        this.hideSection('configSection');
    }

    async processFiles() {
        if (AppState.files.length === 0) return;

        if (AppState.processing) return;

        if (AppState.currentConfig.pageRange === 'custom') {
            const rangeCheck = this.validateCustomRange(AppState.currentConfig.customRange);
            if (!rangeCheck.valid) {
                alert(rangeCheck.message);
                return;
            }
            AppState.currentConfig.customRange = rangeCheck.value;
        }

        this.showSection('processingSection');
        this.hideSection('configSection');
        this.hideSection('resultsSection');
        this.hideSection('previewSection');
        
        AppState.processing = true;
        AppState.processingHidden = false;
        AppState.processedFiles = [];
        AppState.previewIndex = 0;

        // Compute which page numbers (1-based) are selected for preview
        const filteredPages = this.computeFilteredPages();

        const processingConfig = {
            inversionMode: AppState.currentConfig.inversionMode,
            pageRange: AppState.currentConfig.pageRange,
            customRange: AppState.currentConfig.customRange,
            outputDpi: AppState.currentConfig.outputDpi,
            compress: AppState.currentConfig.compress
        };

        try {
            // Simulate processing with progress updates
            const totalFiles = AppState.files.length;
            let processedCount = 0;

            for (const fileData of AppState.files) {
                processedCount++;
                
                // Update status
                this.elements.processingStatus.textContent = 
                    `Processing ${fileData.name} (${processedCount} of ${totalFiles})...`;
                
                // Update progress
                const progress = (processedCount / totalFiles) * 100;
                this.elements.progressFill.style.width = `${progress}%`;

                // Actual API call would go here
                await this.processFile(fileData, processingConfig);
                
                // Save to history
                await storage.saveHistory({
                    fileName: fileData.name,
                    mode: AppState.currentConfig.inversionMode,
                    pageRange: AppState.currentConfig.pageRange,
                    fileSize: fileData.size
                });
            }

            // Processing complete
            AppState.processing = false;
            this.hideSection('processingSection');
            this.hideSection('fileListSection');
            
            // Show preview if we have processed files
            if (AppState.processedFiles.length > 0) {
                // Store filteredPages so preview can use them
                AppState.filteredPages = filteredPages;
                this.updatePreviewSelect();
                this.showSection('previewSection');
                await this.loadPreviewByIndex(0);
            }
            this.updateDownloadButtonLabel();
            this.buildResultsFileList();
            this.showSection('resultsSection');

            AppState.files = [];

        } catch (error) {
            console.error('Processing error:', error);
            alert('An error occurred during processing. Please try again.');
            this.cancelProcessing();
        }
    }

    async processFile(fileData, processingConfig) {
        const formData = new FormData();
        formData.append('file', fileData.file);
        formData.append('mode', processingConfig.inversionMode.toUpperCase().replace(/-/g, '_'));
        formData.append('rangeType', processingConfig.pageRange.toUpperCase());
        
        if (processingConfig.pageRange === 'custom') {
            formData.append('customRange', processingConfig.customRange);
        }

        formData.append('outputDpi', String(processingConfig.outputDpi || 300));
        formData.append('compress', String(processingConfig.compress || false));

        try {
            const response = await fetch(`${API_CONFIG.baseUrl}${API_CONFIG.endpoints.process}`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                const errorText = await response.text();
                console.error('Server error:', errorText);
                throw new Error(`Processing failed: ${response.statusText}`);
            }

            const blob = await response.blob();
            const fileName = fileData.name.replace('.pdf', '_inverted.pdf');

            const existingIndex = AppState.processedFiles.findIndex(
                item => item.originalFile.name === fileData.file.name
            );

            const processedEntry = {
                name: fileName,
                blob: blob,
                originalFile: fileData.file
            };

            if (existingIndex >= 0) {
                AppState.processedFiles[existingIndex] = processedEntry;
            } else {
                AppState.processedFiles.push(processedEntry);
            }
        } catch (error) {
            console.error('API error:', error);
            throw error;
        }
    }

    cancelProcessing() {
        AppState.processingHidden = true;
        this.hideSection('processingSection');
        this.showSection('configSection');
    }

    async downloadResults() {
        if (AppState.processedFiles.length === 0) return;

        if (AppState.processedFiles.length === 1) {
            // Single file download
            const file = AppState.processedFiles[0];
            const url = URL.createObjectURL(file.blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = file.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        } else {
            // ZIP download for multiple files
            if (!window.JSZip) {
                alert('ZIP download requires JSZip. Please refresh the page and try again.');
                return;
            }

            const zip = new JSZip();
            AppState.processedFiles.forEach(file => {
                zip.file(file.name, file.blob);
            });

            const zipBlob = await zip.generateAsync({ type: 'blob' });
            const url = URL.createObjectURL(zipBlob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'pdf_inverter_batch.zip';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    }

    resetApp(openPicker = false) {
        AppState.files = [];
        AppState.processedFiles = [];
        AppState.previewIndex = 0;
        AppState.filteredPages = [];
        AppState.processing = false;
        AppState.processingHidden = false;
        this.elements.fileInput.value = '';
        this.elements.previewFileSelect.innerHTML = '';
        this.elements.previewFileName.textContent = '';
        this.previewController.reset();
        const customRangeInput = document.getElementById('customRange');
        if (customRangeInput) {
            customRangeInput.value = '';
        }
        AppState.currentConfig.customRange = '';
        AppState.currentConfig.outputDpi = 300;
        AppState.currentConfig.compress = false;

        const dpiSelect = document.getElementById('outputDpi');
        if (dpiSelect) dpiSelect.value = '300';
        const compressCheck = document.getElementById('compressOutput');
        if (compressCheck) compressCheck.checked = false;

        // Clear results file list
        if (this.elements.resultsFileList) {
            this.elements.resultsFileList.innerHTML = '';
        }
        
        this.hideSection('fileListSection');
        this.hideSection('configSection');
        this.hideSection('resultsSection');
        this.hideSection('previewSection');
        this.hideSection('processingSection');
        this.showSection('uploadSection');

        if (openPicker) {
            requestAnimationFrame(() => {
                this.elements.fileInput.click();
            });
        }
    }

    validateCustomRange(range) {
        const trimmed = range.trim();
        if (!trimmed) {
            return { valid: false, message: 'Please enter a custom page range.' };
        }
        if (!/^[0-9,\-\s]+$/.test(trimmed)) {
            return { valid: false, message: 'Custom range should include only numbers, commas, and hyphens.' };
        }
        return { valid: true, value: trimmed.replace(/\s+/g, '') };
    }

    updatePreviewSelect() {
        if (!this.elements.previewFileSelect || !this.elements.prevFile || !this.elements.nextFile) return;

        this.elements.previewFileSelect.innerHTML = '';
        AppState.processedFiles.forEach((file, index) => {
            const option = document.createElement('option');
            option.value = String(index);
            option.textContent = file.originalFile.name;
            this.elements.previewFileSelect.appendChild(option);
        });

        this.elements.previewFileSelect.value = String(AppState.previewIndex || 0);
        const showNav = AppState.processedFiles.length > 1;
        this.elements.previewFileSelect.style.display = showNav ? 'inline-flex' : 'none';
        this.elements.prevFile.style.display = showNav ? 'inline-flex' : 'none';
        this.elements.nextFile.style.display = showNav ? 'inline-flex' : 'none';
        this.updatePreviewNavButtons();
    }

    async loadPreviewByIndex(index) {
        const file = AppState.processedFiles[index];
        if (!file) return;

        this.elements.previewFileName.textContent = file.originalFile.name;
        this.previewController.reset();
        await this.previewController.loadPDFs(file.originalFile, file.blob, AppState.filteredPages || []);
        this.updatePreviewNavButtons();
    }

    shiftPreviewFile(delta) {
        if (AppState.processedFiles.length === 0 || !this.elements.previewFileSelect) return;
        const nextIndex = Math.max(
            0,
            Math.min(AppState.processedFiles.length - 1, AppState.previewIndex + delta)
        );
        if (nextIndex === AppState.previewIndex) return;
        AppState.previewIndex = nextIndex;
        this.elements.previewFileSelect.value = String(nextIndex);
        this.loadPreviewByIndex(nextIndex);
    }

    updatePreviewNavButtons() {
        if (!this.elements.prevFile || !this.elements.nextFile) return;
        this.elements.prevFile.disabled = AppState.previewIndex <= 0;
        this.elements.nextFile.disabled = AppState.previewIndex >= AppState.processedFiles.length - 1;
    }

    updateDownloadButtonLabel() {
        const label = document.getElementById('downloadBtnLabel');
        const btn = this.elements.downloadBtn;
        if (!label || !btn) return;
        if (AppState.processedFiles.length === 1) {
            label.textContent = 'Download PDF';
        } else {
            label.textContent = 'Download ZIP';
        }
        // Hide download ZIP button if single file (already has individual download)
        if (AppState.processedFiles.length <= 1) {
            btn.style.display = 'none';
        } else {
            btn.style.display = 'inline-flex';
        }
    }

    /** Build per-file download list in results section */
    buildResultsFileList() {
        const container = this.elements.resultsFileList;
        if (!container) return;
        container.innerHTML = '';

        AppState.processedFiles.forEach((file, index) => {
            const item = document.createElement('div');
            item.className = 'result-file-item';
            item.innerHTML = `
                <div class="result-file-info">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M7 2h7l5 5v11a2 2 0 01-2 2H7a2 2 0 01-2-2V4a2 2 0 012-2z"/>
                        <path d="M14 2v5h5" stroke="white" stroke-width="2" fill="none"/>
                    </svg>
                    <span class="result-file-name">${file.name}</span>
                </div>
                <button class="btn btn-secondary btn-sm result-download-btn" data-index="${index}">
                    <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor">
                        <path fill-rule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clip-rule="evenodd"/>
                    </svg>
                    Download
                </button>
            `;
            item.querySelector('.result-download-btn').addEventListener('click', () => {
                this.downloadSingleFile(index);
            });
            container.appendChild(item);
        });
    }

    /** Download a single processed file by index */
    downloadSingleFile(index) {
        const file = AppState.processedFiles[index];
        if (!file) return;
        const url = URL.createObjectURL(file.blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = file.name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    /** Show/hide page range card depending on file count */
    updatePageRangeVisibility() {
        const card = this.elements.pageRangeCard;
        if (!card) return;
        if (AppState.files.length > 1) {
            card.style.display = 'none';
            // Reset to 'all' when hidden
            document.querySelector('input[name="pageRange"][value="all"]').checked = true;
            AppState.currentConfig.pageRange = 'all';
            document.getElementById('customRangeInput').style.display = 'none';
        } else {
            card.style.display = '';
        }
    }

    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    showSection(sectionId) {
        const section = this.elements[sectionId];
        if (section) {
            section.style.display = 'block';
        }
    }

    hideSection(sectionId) {
        const section = this.elements[sectionId];
        if (section) {
            section.style.display = 'none';
        }
    }

    // Theme Management
    initTheme() {
        storage.init().then(() => {
            storage.getPreference('theme').then(theme => {
                if (theme) {
                    AppState.theme = theme;
                    document.body.className = theme === 'dark' ? 'dark-theme' : '';
                }
            });
        });
    }

    toggleTheme() {
        AppState.theme = AppState.theme === 'dark' ? 'light' : 'dark';
        document.body.className = AppState.theme === 'dark' ? 'dark-theme' : '';
        storage.savePreference('theme', AppState.theme);
    }

    /**
     * Compute which 1-based page numbers should be shown in preview
     * based on current page-range config. We estimate using the first file's page count.
     */
    computeFilteredPages() {
        const range = AppState.currentConfig.pageRange;
        // We don't know exact page count until loaded; for preview we'll compute per-file later.
        // Return a descriptor the preview will resolve per file.
        if (range === 'all') return [];

        // For odd/even/custom, we need the page count. We'll use the first file's count as reference.
        const firstFile = AppState.files[0];
        const totalPages = firstFile ? firstFile.pageCount : 0;
        if (!totalPages) return [];

        const pages = [];
        if (range === 'odd') {
            for (let i = 1; i <= totalPages; i++) { if (i % 2 === 1) pages.push(i); }
        } else if (range === 'even') {
            for (let i = 1; i <= totalPages; i++) { if (i % 2 === 0) pages.push(i); }
        } else if (range === 'custom') {
            const parsed = this.parseCustomRangePages(AppState.currentConfig.customRange, totalPages);
            pages.push(...parsed);
        }
        return pages;
    }

    /**
     * Parse custom range string into 1-based page numbers
     */
    parseCustomRangePages(rangeStr, totalPages) {
        if (!rangeStr) return [];
        const normalized = rangeStr.replace(/\s+/g, '');
        const parts = normalized.split(',');
        const pages = new Set();
        for (const part of parts) {
            if (!part) continue;
            if (part.includes('-')) {
                const [startStr, endStr] = part.split('-');
                let start = parseInt(startStr, 10);
                let end = parseInt(endStr, 10);
                if (isNaN(start) || isNaN(end)) continue;
                if (start > end) { const t = start; start = end; end = t; }
                for (let p = start; p <= end; p++) {
                    if (p >= 1 && p <= totalPages) pages.add(p);
                }
            } else {
                const p = parseInt(part, 10);
                if (!isNaN(p) && p >= 1 && p <= totalPages) pages.add(p);
            }
        }
        return Array.from(pages).sort((a, b) => a - b);
    }
}

// ========================================
// Initialize PDF.js
// ========================================

pdfjsLib.GlobalWorkerOptions.workerSrc = 
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';

// ========================================
// Application Initialization
// ========================================

document.addEventListener('DOMContentLoaded', async () => {
    // Initialize storage
    await storage.init();
    
    // Initialize UI
    const ui = new UIController();
    
    console.log('PDF Inverter initialized');
});

// ========================================
// Service Worker for PWA (Optional)
// ========================================

if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        // navigator.serviceWorker.register('/sw.js')
        //     .then(reg => console.log('Service Worker registered'))
        //     .catch(err => console.log('Service Worker registration failed'));
    });
}
