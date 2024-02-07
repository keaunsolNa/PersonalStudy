package com.greedy.section03.abstraction.dto;

public class Car {

	private boolean isOn;						// 자동차의 구동 상태(true: 시동이 걸린 상태, false: 시동이 안 걸린 상태)
	private int speed;							// 현재 자동차의 속도 상태(현재 속도)
	
	public void startUP() {
		
		/*
		 * 시동이 걸려있는 상태인 경우에는 할 일이 없고,
		 * 시동이 걸려있지 않은 상태인 경우 시동을 걸 것이다.
		 */
		if(this.isOn) {							// 시동이 걸린 상태
			System.out.println("이미 시동이 걸려 있습니다.");
		} else {								// 시동이 안 걸린 상태
			this.isOn  = true;
			System.out.println("시동을 걸었습니다. 이제 출발할 준비가 완료 되었습니다.");
		}
		
	}

	public void go() {
		
		/*
		 *  시동이 걸려있는 상태인 경우 앞으로 가고, 그렇지 않은 경우
		 *  시동을 먼저 걸어야 한다고 할 것이다.
		 */
		if(isOn) {
			this.speed += 10;
			System.out.println("차가 앞으로 움직입니다.");
			System.out.println("현재 차의 시속은 " + this.speed + "kn/h 입니다.");
		} else {
			System.out.println("차의 시동이 걸려있지 않습니다. 시동을 먼저 걸어 주세요.");
		}
	}

	public void stop() {
		
		/* 
		 * 시동이 걸려 있고, 달리는 상태인 경우 멈추고 그렇지 않은 경우 (이미 멈춰있거나 혹은
		 * 히동이 걸리지 않은 상태) 시동을 먼저 걸어야 한다고 할 것이다.
		 */
		if(this.isOn) {
			
			/* 차가 달리고 있는지 아닌지 판단 */
			if(this.speed > 0) {
				this.speed = 0;
				System.out.println("브레이크를 밟았습니다. 차를 멈춥니다.");
			} else {
				System.out.println("차는 이미 멈춰있는 상태입니다.");
			}
		} else {
			System.out.println("차의 시동이 걸려있지 않습니다. 시동을 먼저 걸어주세요.");
		}
		
	}

	public void trunOff() {
		
		/*
		 * 시동이 걸려있는 상태인 경우 시동을 끄고, 이미 꺼진 상태라면 이미 꺼져있다고 알려준다.
		 * 달리고 있는 상태인 경우 우선 차를 멈추라고 안내한다.
		 */
		if(isOn) {
			if(speed>0) {
				System.out.println("달리는 상태에선 시동을 끌 수 없습니다. 차를 우선 멈춰 주세요.");
			} else {
				isOn = false;
				System.out.println("시동을 끕니다. 다시 운행하려면 시동을 켜 주세요.");
			}
		} else {
			System.out.println("이미 시동이 꺼져 있는 상태입니다. 시동 상태를 확인해 주세요.");
		}
	}

}
