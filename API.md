# PDF Inverter API Documentation

## Base URL

When running locally, the API is available at:
```
http://localhost:9090/api
```

The frontend uses relative URLs (`/api`), so it works on any domain automatically.

For production, deploy behind a reverse proxy:
```
https://pdfinverter.com/api
```

---

## Engine (v2)

The backend performs **true PDF manipulation** via Apache PDFBox
content-stream rewriting.  Colour operators (`rg`/`RG`, `g`/`G`, `k`/`K`,
`sc`/`SC`, `scn`/`SCN`) are detected and inverted in-place.  Embedded raster
images are extracted as XObjects, pixel-inverted, and re-embedded.  Form
XObjects are handled recursively.

This preserves:
* Selectable / searchable text
* Vector graphics & print quality
* Original metadata (author, title, bookmarks, etc.)

---

## Authentication

**Current Version**: No authentication required (MVP)

**Future**: JWT-based authentication
```http
Authorization: Bearer <token>
```

---

## Endpoints

### 1. Process Single PDF

Process a single PDF file with color inversion.

**Endpoint**: `POST /pdf/process`

**Content-Type**: `multipart/form-data`

**Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| file | File | Yes | - | PDF file to process |
| mode | String | No | FULL | Inversion mode: `FULL`, `GRAYSCALE`, `TEXT_ONLY`, `CUSTOM` |
| rangeType | String | No | ALL | Page range: `ALL`, `CUSTOM`, `ODD`, `EVEN` |
| customRange | String | No | - | Custom page range (e.g., "1-5,10-20") |
| outputDpi | Integer | No | 300 | Image re-encoding quality (150, 300, 600) |
| compress | Boolean | No | false | Use JPEG compression for embedded images |
| enableOCR | Boolean | No | false | Enable OCR for scanned PDFs |

**Request Example**:

```bash
curl -X POST http://localhost:9090/api/pdf/process \
  -F "file=@document.pdf" \
  -F "mode=FULL" \
  -F "rangeType=CUSTOM" \
  -F "customRange=1-10" \
  -F "outputDpi=300" \
  -F "compress=false" \
  --output inverted.pdf
```

**Success Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="document_inverted_a7b3c9d2.pdf"
Content-Length: 1234567

<PDF binary data>
```

**Error Responses**:

```http
# Invalid file type
HTTP/1.1 400 Bad Request

# File too large
HTTP/1.1 413 Payload Too Large

# Processing error
HTTP/1.1 500 Internal Server Error
```

---

### 2. Process Batch PDFs

Process multiple PDF files and return as ZIP archive.

**Endpoint**: `POST /pdf/batch`

**Content-Type**: `multipart/form-data`

**Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| files[] | File[] | Yes | - | Array of PDF files (max 20) |
| mode | String | No | FULL | Inversion mode |
| rangeType | String | No | ALL | Page range type |

**Request Example**:

```bash
curl -X POST http://localhost:9090/api/pdf/batch \
  -F "files=@doc1.pdf" \
  -F "files=@doc2.pdf" \
  -F "files=@doc3.pdf" \
  -F "mode=GRAYSCALE" \
  -F "rangeType=ALL" \
  --output processed_pdfs.zip
```

**Success Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/zip
Content-Disposition: attachment; filename="processed_pdfs.zip"
Content-Length: 9876543

<ZIP binary data>
```

**Limits**:
- Maximum 20 files per batch
- Maximum 50MB per file
- Maximum 500MB total batch size

---

### 3. Health Check

Check if the API is running.

**Endpoint**: `GET /pdf/health`

**Request Example**:

```bash
curl http://localhost:9090/api/pdf/health
```

**Success Response**:

```http
HTTP/1.1 200 OK
Content-Type: text/plain

PDF Inverter API is running
```

---

### 4. API Information

Get API version and capabilities.

**Endpoint**: `GET /pdf/info`

**Request Example**:

```bash
curl http://localhost:9090/api/pdf/info
```

**Success Response**:

```json
{
  "version": "1.0.0",
  "service": "PDF Color Inverter",
  "supportedModes": [
    "FULL",
    "GRAYSCALE",
    "TEXT_ONLY",
    "CUSTOM"
  ],
  "supportedRanges": [
    "ALL",
    "CUSTOM",
    "ODD",
    "EVEN"
  ],
  "maxFileSize": 52428800,
  "maxBatchSize": 20
}
```

---

## Inversion Modes

### FULL
Complete color inversion of all elements.

**Use Cases**:
- General-purpose inversion
- Eye comfort while reading
- Dark mode conversion

**Formula**: 
- RGB: (R', G', B') = (255-R, 255-G, 255-B)
- Grayscale: G' = 255 - G

---

### GRAYSCALE
Convert to grayscale, then invert.

**Use Cases**:
- Print-friendly documents
- Consistent contrast
- Removing color distractions

**Process**:
1. Convert to grayscale: G = 0.299R + 0.587G + 0.114B
2. Invert: G' = 255 - G

---

### TEXT_ONLY
Invert text and backgrounds, preserve images.

**Use Cases**:
- Documents with colored diagrams
- Preserving photo quality
- Selective inversion

**Logic**:
- Inverts text rendering operators
- Inverts fill/stroke colors
- Skips image XObjects

---

### CUSTOM
Custom dark mode optimized for readability.

**Use Cases**:
- Maximum eye comfort
- Long reading sessions
- Professional documents

**Colors**:
- Background: #2A2A2A (dark gray)
- Text: #E8E8E8 (off-white)
- Contrast ratio: 12:1 (WCAG AAA)

---

## Page Range Syntax

### ALL
Process all pages in the document.

**Example**: All pages from 1 to N

---

### CUSTOM
Specify exact page ranges.

**Syntax**: `"start-end,start-end,page"`

**Examples**:
- `"1-5"` → Pages 1, 2, 3, 4, 5
- `"1,3,5"` → Pages 1, 3, 5
- `"1-10,20-30"` → Pages 1-10 and 20-30
- `"1-5,10,15-20"` → Pages 1-5, 10, and 15-20

---

### ODD
Process only odd-numbered pages.

**Example**: 1, 3, 5, 7, 9, ...

---

### EVEN
Process only even-numbered pages.

**Example**: 2, 4, 6, 8, 10, ...

---

## Error Handling

### Error Response Format

```json
{
  "timestamp": "2026-02-08T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid file type. Only PDF files are supported.",
  "path": "/api/pdf/process"
}
```

### Common Error Codes

| Code | Error | Description |
|------|-------|-------------|
| 400 | Bad Request | Invalid parameters or file type |
| 413 | Payload Too Large | File exceeds size limit |
| 415 | Unsupported Media Type | Wrong content type |
| 422 | Unprocessable Entity | Corrupted PDF or processing error |
| 500 | Internal Server Error | Server-side processing failure |
| 503 | Service Unavailable | Server overloaded |

---

## Rate Limiting

**Current**: No rate limiting (MVP)

**Recommended for Production**:
```
- 100 requests per hour per IP
- 10 concurrent processing requests
- 1GB total upload per day per IP
```

**Headers**:
```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1675862400
```

---

## CORS Policy

**Allowed Origins** (Development):
```
http://localhost:9090
```

Since the frontend is served from the same origin as the API, CORS is not required in the default setup.

**Allowed Methods**:
```
GET, POST, OPTIONS
```

**Allowed Headers**:
```
Content-Type, Authorization
```

For production with cross-origin access, set the `CORS_ORIGINS` environment variable:

```bash
CORS_ORIGINS=https://example.com,https://app.example.com java -jar target/pdf-inverter-backend-1.0.0.jar
```

---

## Best Practices

### 1. File Validation
Always validate file type on client side before uploading:
```javascript
if (file.type !== 'application/pdf') {
    alert('Please select a PDF file');
    return;
}
```

### 2. Progress Tracking
For large files, implement client-side progress tracking:
```javascript
xhr.upload.onprogress = (e) => {
    const percent = (e.loaded / e.total) * 100;
    updateProgressBar(percent);
};
```

### 3. Error Handling
Always handle errors gracefully:
```javascript
try {
    const response = await fetch('/api/pdf/process', {...});
    if (!response.ok) {
        throw new Error('Processing failed');
    }
} catch (error) {
    console.error('Error:', error);
    alert('An error occurred. Please try again.');
}
```

### 4. Timeout Handling
Set appropriate timeouts for large files:
```javascript
const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), 300000); // 5 min

fetch('/api/pdf/process', {
    signal: controller.signal,
    ...
});
```

---

## Integration Examples

### JavaScript/Fetch

```javascript
async function processPDF(file, mode = 'FULL') {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('mode', mode);
    formData.append('rangeType', 'ALL');

    const response = await fetch('/api/pdf/process', {
        method: 'POST',
        body: formData
    });

    if (!response.ok) {
        throw new Error('Processing failed');
    }

    const blob = await response.blob();
    return blob;
}
```

### Python/Requests

```python
import requests

def process_pdf(file_path, mode='FULL'):
    with open(file_path, 'rb') as f:
        files = {'file': f}
        data = {
            'mode': mode,
            'rangeType': 'ALL'
        }
        
        response = requests.post(
            'http://localhost:9090/api/pdf/process',
            files=files,
            data=data
        )
        
        if response.status_code == 200:
            with open('inverted.pdf', 'wb') as output:
                output.write(response.content)
            return True
        return False
```

### cURL

```bash
# Simple processing
curl -X POST http://localhost:9090/api/pdf/process \
  -F "file=@input.pdf" \
  --output output.pdf

# With all options
curl -X POST http://localhost:9090/api/pdf/process \
  -F "file=@input.pdf" \
  -F "mode=CUSTOM" \
  -F "rangeType=CUSTOM" \
  -F "customRange=1-10,20-30" \
  -F "outputDpi=300" \
  -F "compress=true" \
  --output output.pdf

# Health check
curl http://localhost:9090/api/pdf/health
```

---

## Monitoring & Logging

### Request Logging

All requests are logged with:
- Timestamp
- Endpoint
- File name
- File size
- Processing time
- Status code

**Example Log**:
```
2026-02-08 10:30:15 - Received PDF processing request: document.pdf (1.2MB)
2026-02-08 10:30:17 - PDF processed successfully in 2134ms: document_inverted_a7b3c9d2.pdf
```

### Health Monitoring

Monitor these metrics:
- Request rate (requests/second)
- Processing time (milliseconds)
- Error rate (errors/total requests)
- Memory usage
- Disk usage (temp directory)

---

## Security Considerations

### Input Validation
- File type check (PDF only)
- File size limits (50MB default)
- Malicious PDF detection
- Content sanitization

### File Handling
- Temporary files auto-deleted after 1 hour
- Unique file names prevent collisions
- No permanent storage of user files

### Network Security
- HTTPS required in production
- CORS restrictions
- Rate limiting
- DDoS protection

---

## Changelog

### Version 1.0.0 (Current)
- Initial release
- Single PDF processing
- Batch processing
- Four inversion modes
- Page range selection
- Health check endpoint

### Planned Features
- Webhook support for async processing
- Processing status endpoint
- User authentication
- File download by ID
- Processing history API

---

**API Version**: 1.0.0  
**Last Updated**: February 2026
