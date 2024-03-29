# Auxiliary Memory

# Auxiliary Memory

- 보조 메모리(Auxiliary Memory)는 컴퓨터 시스템에서 가장 저렴하고, 가장 많은 공간을 가지며, 가장 느리게 접근하는 비휘발성 메모리 스토리지다.
- 장기간 보관하거나 직접 사용하지 않을 때 프로그램과 정보가 보존되는 곳이다.
- 순차 액세스와 직접 액세스의 두 가지 유형으로 나뉜다.
- auxiliary storage, secondary storage, secondary memory, external storage, external memory 라고도 한다.
- 보조 메모리는 CPU에서 직접 액세스할 수 없다. 대신, 필요할 때마다 사용되는 고대역폭 채널을 통해 보조 메모리에서 기본 메모리(primary memory)로 제공되는 중요하지 않은 시스템 데이터를 저장한다.
- 보조 메모리는 향후 사용을 위해 데이터를 보관하며 정전이 되어도 정보를 유지한다.
- 이는 나중에 동일한 컴퓨터나 다른 컴퓨터에서 이 정보를 읽을 수 있도록 하기 위한 것으로, 백업이나 정보의 이동에 유용하다.
- 보조 메모리에는 자기 테이프, 자기 디스크, 광 디스크 등이 있다.

# Magnetic Tape

![Untitled](Auxiliary_Memory/Untitled.png)

- Magnetic Tapes(자기 테이프)는 전자 데이터 저장을 위한 가장 오래된 기술 중 하나로, 자화(magnetizable) 가능한 물질로 코팅된 얇고 길고 좁은 플라스틱 스트립이다.
- 리본의 한쪽 면만 데이터를 저장하는 데 사용된다.
- 순차 액세스가 이루어지기 때문에 데이터 읽기/쓰기 속도가 매우 느리다.
- 저장된 데이터는 사람이 읽을 수 있는 형식이 아니므로 수동 인코딩이 불가능하다.
- 먼지나 부주의한 취급으로 인해 손상되기 쉽다.
- 데이터 접근 속도가 느려 주로 백업 메모리로 활용된다.
- 비휘발성 메모리다.
- HDD, 디스크 등에 비해 보존 가능 기한이 길다.
- 드라이버에 삽입하지 않는 한 읽거나 수정할 수 없으므로 사이버 공격과 네트워크 바이러스로부터 안전하다.
- 테이프는 타 저장 장치에 비해 매우 저렴하다.
- 대용량 저장에 적합하다.
- 특정 데이터를 제거하고 같은 장소에 다른 데이터를 저장할 수 있다.

# Magnetic Disk

![Untitled](Auxiliary_Memory/Untitled%201.png)

- Magnetic Disk(자기 디스크)는 산화철과 같은 자성 물질로 코딩 되어있다. 단단한 알루미늄이나 유리로 만든 하드 디스크와 유연한 플라스틱으로 만든 이동식 디스켓의 두 가지 유형이 있다.
- Tracks, spots, sectors의 형태로 데이터를 저장한다.
- 회전하는 자기 표면(platter)과 그 위에서 움직이는 기계 팔로 구성된다. 그 둘로 빗(comb)을 형성한다.
- 기계 팔은 디스크를 읽고 쓰는데 사용되며, 데이터는 자화 과정을 통해 읽고 쓰여진다.
- Platter는 기계 팔의 머리 부분이 표면을 가로질러 움직이는 동안 고속으로 계속 회전한다. 헤드에 작은 전류를 가하면 디스크 표면의 작은 점들이 자화 되는 방식으로 데이터를 저장한다.
- 한 방향의 편광 정보는 1로 표시되고 그 반대의 경우도 마찬가지다. 방향은 0으로 표시된다.
- 저장된 데이터는 사람이 읽을 수 있는 형식이 아니므로 수동 인코딩이 불가능하다.
- 명시적으로 기록에 접근할 수 있으므로 액세스 시간이 짧다.
- 자기 테이프에 비해 휴대성이 매우 떨어진다.
- 저장할 수 있는 레코드 기간은 디스크 트랙 또는 디스크 섹터의 크기에 따라 제한된다.
- 데이터 전송 속도가 빠르다.
- 특정 데이터를 제거하고 같은 장소에 다른 데이터를 저장할 수 있다.

# O**ptical Disk**

- Optical Disk(광 디스크)는 저전력 레이저 빔 데이터를 읽고 쓰는 컴퓨터 디스크다.
- CD, DVD, Blu-ray 등의 형태가 있다.
- 데이터를 디지털 방식으로 저장하고 레이저 빔(광 디스크 드라이브에 장착된 레이저 헤드에서 전송, 빨간색 또는 파란색)을 사용하여 데이터를 읽고 쓰는 컴퓨터 저장 디스크이다.
- 데이터는 미세한 데이터 피트(pits)와 랜드(lands) 형태로 디스크에 저장된다.
- 피트는 기록 재료의 반사층에 etched 된다.
- land는 pit 주변의 평평하고 움푹 들어간 곳이 없는 지역이다.
- 빛이 녹음 자료에 어떻게 반사되는지에 따라 피트와 랜드를 구분하며, 드라이브는 반사율의 차이를 사용하여 데이터를 나타내는 0과 1 비트를 결정한다.
- 주로 휴대용 및 보조 저장 장치로 사용되며 이전 세대의 자기 저장 매체보다 더 많은 데이터를 저장할 수 있으며, 상대적으로 수명도 길다.
- 자기 교란이나 power surge(전압 과다) 와 같은 대부분의 환경적 위협에 영향을 받지 않는다.
- 긁힘, 열 등 물리적인 손상에 대해서는 취약하다
- 데이터 백업 프로세스가 쉽다.
- 제조 비용이 저렴하다.