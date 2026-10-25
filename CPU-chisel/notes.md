| 通道 | 用途 | 数据方向 | CPU 驱动的信号 | 内存驱动的信号 |
|---|---|---|---|---|
| AR | 发送读地址 | CPU → 内存 | `araddr, arvalid` | `arready` |
| R | 返回读取结果 | 内存 → CPU | `rready` | `rdata, rresp, rvalid` |
| AW | 发送写地址 | CPU → 内存 | `awaddr, awvalid` | `awready` |
| W | 发送写入数据 | CPU → 内存 | `wdata, wstrb, wvalid` | `wready` |
| B | 返回写操作响应 | 内存 → CPU | `bready` | `bresp, bvalid` |