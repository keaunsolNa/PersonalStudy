package com.greedy.section04.array_sort;

public class Application3 {

    public static void main(String[] args) {

        /* 선택 정렬(select sort) */
        /* 배열을 전부 탐색하여 최소값을 고르고 왼쪽부터 채워나가는 방식의 정렬
         * 데이터의 양이 적을 때 좋은 성능을 나타낸다(교환회수가 적음)
         * 하지만 배열을 전부 탐색하여 최소값을 찾아야 하기 때문에 100개 이상의 자료에서는 급격하게 속도가 저하된다.
         * */
        
        /* 초기 배열 선언 및 초기화 */
        int[] iarr = {2, 5, 4, 6, 1, 3};
        
        int min;         //최소값을 가진 데이터의 인덱스 저장 변수
        int temp;
        
        for(int i = 0; i < iarr.length - 1; i++){
            min = i;
            /* 해당 인덱스 다음 인덱스부터 끝까지 반복을 한다 */
            for(int j = i + 1; j < iarr.length; j++){
                
                /* i 의 인덱스 보다 작은 값이 있는 인덱스의 경우 min을 변경한다 */
                if(iarr[min] > iarr[j]){
                    min = j;
                }
            }
            
            /* 작은 값을 시작 인덱스와 교환한다*/
            temp = iarr[min];
            iarr[min] = iarr[i];
            iarr[i] = temp;

        }
        
        /* 값 출력용 반복문 */
        for(int i = 0; i < iarr.length; i++){
            System.out.print(iarr[i] + " ");
        }

    }

}