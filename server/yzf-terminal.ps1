param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 0,
    [string]$Token = ""
)
$ErrorActionPreference = "Stop"
if ($Port -le 0) { $Port = [int](Read-Host "YZF command socket port") }
if ([string]::IsNullOrWhiteSpace($Token)) { $Token = Read-Host "YZF token" }
$client = [Net.Sockets.TcpClient]::new()
$client.Connect($HostName, $Port)
$stream = $client.GetStream()
$reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
$writer = [IO.StreamWriter]::new($stream, [Text.Encoding]::UTF8)
$writer.AutoFlush = $true
if ($Token) { $writer.WriteLine("AUTH $Token") }
Write-Host "Connected to YZF $HostName`:$Port. Type commands; Ctrl+C exits."
$readThread = [Threading.Thread]::new([Threading.ThreadStart]{ while (($line = $reader.ReadLine()) -ne $null) { [Console]::WriteLine($line) } })
$readThread.IsBackground = $true
$readThread.Start()
try {
    while ($client.Connected) {
        $line = [Console]::ReadLine()
        if ($null -eq $line) { break }
        if ($line.Trim()) { $writer.WriteLine($line) }
    }
} finally {
    $writer.Dispose(); $reader.Dispose(); $client.Dispose()
}
